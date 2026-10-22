package com.timborja.tugma.data

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.ConfirmedPlan
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.SpeakerSource
import com.timborja.tugma.linkplay.LinkplayClient
import com.timborja.tugma.linkplay.LinkplayDevice
import com.timborja.tugma.linkplay.LinkplayExtras
import com.timborja.tugma.linkplay.LinkplayHousehold
import com.timborja.tugma.linkplay.demo.FakeLinkplayHousehold
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.LoggingTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Linkplay-platform speakers (Arylic, WiiM, Audio Pro…): playback, multiroom groups and channels. */
class LinkplayRepository(
    private val settings: Settings,
    private val log: CommandLog,
    private val realTransport: HttpTransport,
    private val realDiscoverer: Discoverer,
) : SpeakerSource {
    override val brand: Brand = Brand.LINKPLAY

    private var fake = FakeLinkplayHousehold(latencyMillis = 80)
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<LinkplayDevice>>(emptyList())
    val devices: StateFlow<List<LinkplayDevice>> = _devices.asStateFlow()

    private val _summaries = MutableStateFlow(SourceState())
    override val summaries: StateFlow<SourceState> = _summaries.asStateFlow()

    private val demo get() = settings.demoMode.value
    private fun client() = LinkplayClient(if (demo) LoggingTransport(fake, log) else realTransport)

    override suspend fun refresh() = mutex.withLock {
        _summaries.update { it.copy(loading = true, error = null) }
        try {
            // Linkplay speakers are DLNA renderers too, so the SSDP renderer search finds their hosts.
            val discovered = (if (demo) fake else realDiscoverer).search().map { it.host }
            // Plus speakers added by IP, for networks that block SSDP. Non-Linkplay hosts just don't answer the probe.
            val manual = if (demo) emptyList() else settings.manualHosts.value.map { it.substringBefore(':') }
            val hosts = (discovered + manual).distinct()
            val found = LinkplayHousehold(client()).load(hosts)
            _devices.value = found
            _summaries.value = SourceState(speakers = found.map { it.toSummary() }, loadedOnce = true)
        } catch (e: Exception) {
            _summaries.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName, loadedOnce = true) }
        }
    }

    override fun playback(id: String): PlaybackControl? = _devices.value.firstOrNull { it.uuid == id }?.let { client().playback(it) }

    /** Presets and EQ for one speaker. */
    fun extras(id: String): LinkplayExtras? = device(id)?.let { LinkplayExtras(client(), it) }

    fun device(id: String): LinkplayDevice? = _devices.value.firstOrNull { it.uuid == id }

    /** Sends a confirmed grouping/channel plan, then rescans. */
    suspend fun execute(confirmed: ConfirmedPlan): Result<Unit> {
        val result = runCatching { client().execute(confirmed) }
        refresh()
        return result
    }

    fun clear() {
        _devices.value = emptyList()
        _summaries.value = SourceState()
    }

    fun resetDemo() {
        fake = FakeLinkplayHousehold(latencyMillis = 80)
        clear()
    }
}
