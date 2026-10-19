package com.timborja.tugma.data

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.SpeakerSource
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.LoggingTransport
import com.timborja.tugma.upnp.demo.FakeDlnaHousehold
import com.timborja.tugma.upnp.dlna.DlnaClient
import com.timborja.tugma.upnp.dlna.DlnaDiscovery
import com.timborja.tugma.upnp.dlna.DlnaRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Any brand's Wi-Fi speaker that exposes a standard UPnP/DLNA MediaRenderer (Sonos excluded). */
class DlnaRepository(
    private val settings: Settings,
    private val log: CommandLog,
    private val realTransport: HttpTransport,
    private val realDiscoverer: Discoverer,
) : SpeakerSource {
    override val brand: Brand = Brand.UPNP

    private var fake = FakeDlnaHousehold(latencyMillis = 120)
    private val mutex = Mutex()
    private val renderers = MutableStateFlow<List<DlnaRenderer>>(emptyList())

    private val _summaries = MutableStateFlow(SourceState())
    override val summaries: StateFlow<SourceState> = _summaries.asStateFlow()

    private val demo get() = settings.demoMode.value
    private fun client() = DlnaClient(if (demo) LoggingTransport(fake, log) else realTransport)

    override suspend fun refresh() = mutex.withLock {
        _summaries.update { it.copy(loading = true, error = null) }
        try {
            val found = DlnaDiscovery(client(), if (demo) fake else realDiscoverer).discover()
            renderers.value = found
            _summaries.value = SourceState(speakers = found.map { it.toSummary() }, loadedOnce = true)
        } catch (e: Exception) {
            _summaries.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName, loadedOnce = true) }
        }
    }

    override fun playback(id: String): PlaybackControl? =
        renderers.value.firstOrNull { it.id == id }?.let { client().playback(it) }

    fun clear() {
        renderers.value = emptyList()
        _summaries.value = SourceState()
    }

    fun resetDemo() {
        fake = FakeDlnaHousehold(latencyMillis = 120)
        clear()
    }
}
