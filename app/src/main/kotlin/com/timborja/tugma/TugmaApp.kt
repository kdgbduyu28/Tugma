package com.timborja.tugma

import android.app.Application
import android.content.Context
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.InMemoryCommandLog
import com.timborja.tugma.core.SpeakerSource
import com.timborja.tugma.cast.TlsCastConnector
import com.timborja.tugma.data.CastRepository
import com.timborja.tugma.data.DlnaRepository
import com.timborja.tugma.data.NsdCastDiscoverer
import com.timborja.tugma.data.MulticastLockDiscoverer
import com.timborja.tugma.data.NetworkMonitor
import com.timborja.tugma.data.Settings
import com.timborja.tugma.data.SnapshotStore
import com.timborja.tugma.data.SonosRepository
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.upnp.LanOnlyTransport
import com.timborja.tugma.upnp.LoggingTransport
import com.timborja.tugma.upnp.OkHttpTransport
import com.timborja.tugma.upnp.SsdpDiscoverer
import com.timborja.tugma.upnp.dlna.UpnpServices

/** Manual DI: one container per process, created in [TugmaApp]. */
class AppContainer(context: Context) {
    val settings = Settings(context)
    val log: CommandLog = InMemoryCommandLog()
    val network = NetworkMonitor(context)
    val snapshots = SnapshotStore(context)
    private val lanTransport = LoggingTransport(LanOnlyTransport(OkHttpTransport()), log)
    val sonos = SonosRepository(
        settings = settings,
        log = log,
        realTransport = lanTransport,
        realDiscoverer = MulticastLockDiscoverer(context, SsdpDiscoverer(SonosClient.ZONE_PLAYER_ST)),
        snapshots = snapshots,
    )
    val dlna = DlnaRepository(
        settings = settings,
        log = log,
        realTransport = lanTransport,
        realDiscoverer = MulticastLockDiscoverer(context, SsdpDiscoverer(UpnpServices.MEDIA_RENDERER_ST)),
    )

    val cast = CastRepository(
        settings = settings,
        log = log,
        realConnector = TlsCastConnector(),
        realDiscoverer = NsdCastDiscoverer(context),
    )

    /** Every brand's speakers. New adapters (Linkplay…) get added here. */
    val sources: List<SpeakerSource> = listOf(sonos, dlna, cast)

    fun source(brand: Brand): SpeakerSource? = sources.firstOrNull { it.brand == brand }

    /** Demo mode swaps every source between real and fake speakers. */
    fun setDemoMode(on: Boolean) {
        settings.setDemoMode(on)
        sonos.clear()
        dlna.clear()
        cast.clear()
    }

    fun resetDemo() {
        sonos.resetDemo()
        dlna.resetDemo()
        cast.resetDemo()
    }
}

class TugmaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
