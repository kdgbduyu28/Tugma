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
import com.timborja.tugma.sonos.SonosAlarms
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.sonos.roomGroups
import com.timborja.tugma.upnp.dlna.NowPlaying
import com.timborja.tugma.sonos.pauseAllGroups
import com.timborja.tugma.sonos.resume
import com.timborja.tugma.sonos.SonosFavorites
import com.timborja.tugma.sonos.SonosInputs
import com.timborja.tugma.sonos.SonosPlayer
import com.timborja.tugma.sonos.SonosQueue
import com.timborja.tugma.sonos.SonosSettings
import com.timborja.tugma.sonos.SonosSleepTimer
import com.timborja.tugma.sonos.SonosGroupVolume
import com.timborja.tugma.sonos.SonosSound
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

    /** Sound settings on a visible room (a bond's left speaker or soundbar applies them to the whole bond). */
    fun sound(id: String): SonosSound? {
        val s = _state.value.household?.speaker(id)?.takeIf { !it.invisible } ?: return null
        return client().sound(s.endpoint)
    }

    /** Volume for the whole group [id] belongs to, sent to its coordinator. */
    fun groupVolume(id: String): SonosGroupVolume? {
        val h = _state.value.household ?: return null
        return h.coordinatorOf(id)?.let { client().groupVolume(it.endpoint) }
    }

    /** Any visible player, for household-wide services (alarms, favorites). */
    private fun anyPlayer(): SonosSpeaker? = _state.value.household?.speakers?.firstOrNull { !it.invisible }

    fun alarms(): SonosAlarms? = anyPlayer()?.let { client().alarms(it.endpoint) }
    fun favorites(): SonosFavorites? = anyPlayer()?.let { client().favorites(it.endpoint) }

    /** Plays on [id]'s group coordinator. */
    fun player(id: String): SonosPlayer? = _state.value.household?.coordinatorOf(id)?.let { client().player(it.endpoint, it.uuid) }
    fun queue(id: String): SonosQueue? = _state.value.household?.coordinatorOf(id)?.let { client().queue(it.endpoint, it.uuid) }
    fun inputs(id: String): SonosInputs? = _state.value.household?.coordinatorOf(id)?.let { client().inputs(it.endpoint) }

    /** Settings on the visible player itself (a pair's left speaker applies them to the pair). */
    fun settings(id: String): SonosSettings? =
        _state.value.household?.speaker(id)?.takeIf { !it.invisible }?.let { client().settings(it.endpoint) }

    fun sleepTimer(id: String): SonosSleepTimer? = _state.value.household?.coordinatorOf(id)?.let { client().sleepTimer(it.endpoint) }

    /** What each group plays, keyed by coordinator UUID. Light: two requests per group. */
    suspend fun nowPlaying(): Map<String, NowPlaying> {
        val h = _state.value.household ?: return emptyMap()
        return h.roomGroups().mapNotNull { g ->
            runCatching { g.coordinator.uuid to client().playback(g.coordinator.endpoint, g.coordinator.endpoint).nowPlaying() }.getOrNull()
        }.toMap()
    }

    /** Event URLs for live updates: AVTransport on the group coordinator, RenderingControl on the speaker. Null in demo mode. */
    fun eventUrls(id: String): Pair<String, String>? {
        if (demo) return null
        val h = _state.value.household ?: return null
        val speaker = h.speaker(id)?.takeIf { !it.invisible } ?: return null
        val coordinator = h.coordinatorOf(id) ?: speaker
        return coordinator.endpoint.baseUrl + AVT_EVENT to speaker.endpoint.baseUrl + RC_EVENT
    }

    /** GroupRenderingControl event URL per multi-room group's coordinator. Empty in demo mode. */
    fun groupVolumeEventUrls(): Map<String, String> {
        if (demo) return emptyMap()
        val h = _state.value.household ?: return emptyMap()
        return h.roomGroups().filter { it.members.isNotEmpty() }
            .associate { it.coordinator.uuid to it.coordinator.endpoint.baseUrl + "/MediaRenderer/GroupRenderingControl/Event" }
    }

    /** AVTransport event URL per group coordinator, for live now-playing. Empty in demo mode. */
    fun coordinatorEventUrls(): Map<String, String> {
        if (demo) return emptyMap()
        val h = _state.value.household ?: return emptyMap()
        return h.roomGroups().associate { it.coordinator.uuid to it.coordinator.endpoint.baseUrl + AVT_EVENT }
    }

    /** Groups the last "Pause all" stopped, so "Resume" restarts only those. */
    private val _paused = MutableStateFlow<List<SonosSpeaker>>(emptyList())
    val paused: StateFlow<List<SonosSpeaker>> = _paused.asStateFlow()

    /** Pauses every playing group; returns how many. */
    suspend fun pauseAll(): Int {
        val h = _state.value.household ?: return 0
        _paused.value = client().pauseAllGroups(h)
        return _paused.value.size
    }

    suspend fun resumePaused(): Int = client().resume(_paused.value).also { _paused.value = emptyList() }

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

    private companion object {
        const val AVT_EVENT = "/MediaRenderer/AVTransport/Event"
        const val RC_EVENT = "/MediaRenderer/RenderingControl/Event"
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
