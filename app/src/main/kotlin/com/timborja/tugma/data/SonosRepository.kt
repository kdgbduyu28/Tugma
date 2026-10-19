package com.timborja.tugma.data

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.ConfirmedPlan
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.SpeakerSource
import com.timborja.tugma.sonos.Endpoint
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.NoSpeakersFoundException
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.sonos.SonosHousehold
import com.timborja.tugma.sonos.SonosSpeaker
import com.timborja.tugma.sonos.TopologySnapshot
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.LoggingTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class HouseholdUi(
    val loading: Boolean = false,
    val household: HouseholdState? = null,
    val error: String? = null,
    /** True once at least one load finished, so empty states don't flash on launch. */
    val loadedOnce: Boolean = false,
)

/**
 * Single source of truth for the Sonos household. Every write goes through [execute],
 * which saves a snapshot first and re-reads topology afterwards.
 */
class SonosRepository(
    private val settings: Settings,
    private val log: CommandLog,
    private val realTransport: HttpTransport,
    private val realDiscoverer: Discoverer,
    private val snapshots: SnapshotStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : SpeakerSource {
    override val brand: Brand = Brand.SONOS

    private var fake = FakeSonosHousehold(latencyMillis = 150)
    private val mutex = Mutex()
    private val actionCache = mutableMapOf<String, Set<String>?>()

    private val _state = MutableStateFlow(HouseholdUi())
    val state: StateFlow<HouseholdUi> = _state.asStateFlow()

    private val _summaries = MutableStateFlow(SourceState())
    override val summaries: StateFlow<SourceState> = _summaries.asStateFlow()

    private fun publish(ui: HouseholdUi) {
        _state.value = ui
        _summaries.value = SourceState(
            loading = ui.loading,
            speakers = ui.household?.speakers?.map { it.toSummary() }.orEmpty(),
            error = ui.error,
            loadedOnce = ui.loadedOnce,
        )
    }

    /** Volume/mute on the speaker itself, transport on its group coordinator. */
    override fun playback(id: String): PlaybackControl? {
        val h = _state.value.household ?: return null
        val speaker = h.speaker(id) ?: return null
        val coordinator = h.coordinatorOf(id) ?: speaker
        return client().playback(speaker.endpoint, coordinator.endpoint)
    }

    private val demo get() = settings.demoMode.value
    private fun transport(): HttpTransport = if (demo) LoggingTransport(fake, log) else realTransport
    private fun client() = SonosClient(transport())
    private fun household() = SonosHousehold(client(), if (demo) fake else realDiscoverer, clock)

    fun resetDemo() {
        fake = FakeSonosHousehold(latencyMillis = 150)
        publish(HouseholdUi())
    }

    fun clear() {
        actionCache.clear()
        publish(HouseholdUi())
    }

    /** Full refresh: SSDP search + manual hosts + previously known speakers. */
    override suspend fun refresh() = mutex.withLock {
        publish(_state.value.copy(loading = true, error = null))
        try {
            val h = household()
            val discovered = runCatching { h.discover() }.getOrDefault(emptyList())
            val manual = if (demo) emptyList() else settings.manualHosts.value.map(::parseEndpoint)
            val previous = _state.value.household?.let { listOf(it.source) + it.speakers.filter { s -> !s.invisible }.map { s -> s.endpoint } }.orEmpty()
            val seeds = (discovered + manual + previous).distinct()
            if (seeds.isEmpty()) throw NoSpeakersFoundException()
            val state = h.load(seeds)
            publish(HouseholdUi(household = state, loadedOnce = true))
        } catch (e: Exception) {
            publish(_state.value.copy(loading = false, error = describe(e), loadedOnce = true))
        }
    }

    /** Actions declared by the speaker's DeviceProperties service, or null if it couldn't be read. */
    suspend fun deviceActions(speaker: SonosSpeaker): Set<String>? =
        actionCache.getOrPut(speaker.uuid) { runCatching { client().devicePropertiesActions(speaker.endpoint) }.getOrNull() }

    /** Saves a snapshot, sends the confirmed plan, then re-reads topology. */
    suspend fun execute(confirmed: ConfirmedPlan): Result<Unit> {
        val before = _state.value.household
        if (before != null) {
            snapshots.save(TopologySnapshot.of(UUID.randomUUID().toString(), confirmed.plan.title, before, clock()))
        }
        val result = runCatching { client().execute(confirmed) }.map { }
        refresh()
        return result.recoverCatching { throw IllegalStateException(describe(it), it) }
    }

    private fun parseEndpoint(value: String): Endpoint {
        val host = value.substringBefore(':')
        val port = value.substringAfter(':', "").toIntOrNull() ?: SonosClient.DEFAULT_PORT
        return Endpoint(host, port)
    }

    private fun describe(e: Throwable): String = when (e) {
        is NoSpeakersFoundException -> "No speakers found."
        else -> e.message ?: e.javaClass.simpleName
    }
}
