package com.timborja.tugma.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.timborja.tugma.cast.CastClient
import com.timborja.tugma.cast.CastConnector
import com.timborja.tugma.cast.CastDevice
import com.timborja.tugma.cast.CastDiscoverer
import com.timborja.tugma.cast.CastPlayback
import com.timborja.tugma.cast.demo.FakeCastHousehold
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.SpeakerSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Chromecast-built-in speakers, displays and Google Home groups. Control only; pairing stays in Google Home. */
class CastRepository(
    private val settings: Settings,
    private val log: CommandLog,
    private val realConnector: CastConnector,
    private val realDiscoverer: CastDiscoverer,
) : SpeakerSource {
    override val brand: Brand = Brand.CAST

    private var fake = FakeCastHousehold(latencyMillis = 100)
    private val mutex = Mutex()
    private val devices = MutableStateFlow<List<CastDevice>>(emptyList())

    private val _summaries = MutableStateFlow(SourceState())
    override val summaries: StateFlow<SourceState> = _summaries.asStateFlow()

    private val demo get() = settings.demoMode.value
    private fun client() = CastClient(if (demo) fake else realConnector, log)

    override suspend fun refresh() = mutex.withLock {
        _summaries.update { it.copy(loading = true, error = null) }
        try {
            val found = (if (demo) fake else realDiscoverer).discover()
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.isGroup }, { it.name.lowercase() }))
            devices.value = found
            _summaries.value = SourceState(speakers = found.map { it.toSummary() }, loadedOnce = true)
        } catch (e: Exception) {
            _summaries.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName, loadedOnce = true) }
        }
    }

    override fun playback(id: String): PlaybackControl? =
        devices.value.firstOrNull { it.id == id }?.let { CastPlayback(client(), it.host, it.port) }

    fun clear() {
        devices.value = emptyList()
        _summaries.value = SourceState()
    }

    fun resetDemo() {
        fake = FakeCastHousehold(latencyMillis = 100)
        clear()
    }
}

/**
 * mDNS browse for `_googlecast._tcp` with Android's NsdManager. Resolves one service at a time,
 * because NsdManager before Android 14 rejects parallel resolves.
 */
class NsdCastDiscoverer(context: Context, private val windowMillis: Long = 3_000) : CastDiscoverer {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun discover(): List<CastDevice> {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.close() }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        val services = mutableListOf<NsdServiceInfo>()
        try {
            withTimeoutOrNull(windowMillis) { for (s in found) services += s }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return services.distinctBy { it.serviceName }.mapNotNull { s ->
            val info = resolve(s) ?: return@mapNotNull null
            val host = hostOf(info) ?: return@mapNotNull null
            CastDevice.fromTxt(info.attributes, info.serviceName, host, info.port)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) { if (cont.isActive) cont.resume(resolved) }
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
    }

    /** Prefers an IPv4 address; scoped IPv6 link-local addresses are awkward for sockets and the LAN guard. */
    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress?.substringBefore('%')
    }

    private companion object {
        const val SERVICE_TYPE = "_googlecast._tcp"
    }
}
