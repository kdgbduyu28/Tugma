package com.timborja.tugma.upnp.dlna

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.SpeakerSummary
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.core.UrlPlayback
import com.timborja.tugma.core.VendorAdapter
import com.timborja.tugma.upnp.DeviceDescription
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.Scpd
import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.find
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI

object UpnpServices {
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    const val MEDIA_RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
}

/** One SOAP service on a device: absolute control URL plus its exact service type (with version). */
data class ServiceEndpoint(val controlUrl: String, val serviceType: String, val eventUrl: String? = null)

/** A UPnP/DLNA MediaRenderer, e.g. a Wi-Fi speaker, soundbar, receiver or TV. */
data class DlnaRenderer(
    val id: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val location: String,
    val renderingControl: ServiceEndpoint?,
    val avTransport: ServiceEndpoint?,
    val maxVolume: Int,
    /** Sonos players also answer as MediaRenderers; the Sonos adapter owns those. */
    val isSonos: Boolean,
) {
    val host: String get() = URI(location).host
    val port: Int get() = URI(location).port.takeIf { it > 0 } ?: 80

    val capabilities: Set<Capability>
        get() = buildSet {
            if (renderingControl != null) { add(Capability.VOLUME); add(Capability.MUTE) }
            if (avTransport != null) add(Capability.TRANSPORT)
        }

    fun toSummary(): SpeakerSummary = SpeakerSummary(
        id = id,
        brand = Brand.UPNP,
        name = friendlyName,
        model = listOfNotNull(modelName, modelNumber?.takeIf { it != modelName }).joinToString(" "),
        host = host,
        port = port,
        capabilities = capabilities,
        manufacturer = manufacturer,
    )
}

class DlnaClient(private val transport: HttpTransport) {
    private val soap = SoapClient(transport)

    /** Reads a description at [location]. Returns null if the device has no renderer services. */
    suspend fun describe(location: String): DlnaRenderer? {
        val desc = DeviceDescription.parse(get(location))
        val node = desc.devices.firstOrNull { it.deviceType?.contains(":MediaRenderer:") == true }
            ?: desc.devices.firstOrNull { it.service(UpnpServices.RENDERING_CONTROL) != null || it.service(UpnpServices.AV_TRANSPORT) != null }
            ?: return null
        val rc = node.service(UpnpServices.RENDERING_CONTROL)
        val avt = node.service(UpnpServices.AV_TRANSPORT)
        if (rc == null && avt == null) return null
        val maxVolume = rc?.let { s ->
            runCatching { Scpd.allowedRange(get(desc.resolve(s.scpdUrl, location)), "Volume")?.last }.getOrNull()
        } ?: DEFAULT_MAX_VOLUME
        val udn = (node.udn ?: desc.udn).orEmpty()
        return DlnaRenderer(
            id = udn.removePrefix("uuid:").ifBlank { location },
            friendlyName = node.friendlyName ?: desc.friendlyName ?: URI(location).host,
            manufacturer = node.manufacturer ?: desc.manufacturer,
            modelName = node.modelName ?: desc.modelName,
            modelNumber = node.modelNumber ?: desc.modelNumber,
            location = location,
            renderingControl = rc?.let { endpoint(desc, it, location) },
            avTransport = avt?.let { endpoint(desc, it, location) },
            maxVolume = maxVolume,
            isSonos = desc.devices.any { it.udn?.startsWith("uuid:RINCON_") == true },
        )
    }

    private fun endpoint(desc: DeviceDescription, s: DeviceDescription.ServiceInfo, location: String) = ServiceEndpoint(
        desc.resolve(s.controlUrl, location),
        s.serviceType,
        s.eventSubUrl.takeIf { it.isNotBlank() }?.let { desc.resolve(it, location) },
    )

    fun playback(renderer: DlnaRenderer): PlaybackControl =
        UpnpPlaybackControl(soap, renderer.renderingControl, renderer.avTransport, renderer.maxVolume)

    private suspend fun get(url: String): String {
        val r = transport.execute(HttpRequest("GET", url))
        if (r.status !in 200..299) throw IOException("GET $url returned HTTP ${r.status}")
        return r.body
    }

    companion object {
        const val DEFAULT_MAX_VOLUME = 100
    }
}

/** Finds renderers with SSDP and describes them, skipping Sonos players and anything unreachable. */
class DlnaDiscovery(private val client: DlnaClient, private val discoverer: Discoverer) {
    suspend fun discover(extraLocations: List<String> = emptyList()): List<DlnaRenderer> {
        val locations = (discoverer.search().map { it.location } + extraLocations).distinct()
        return coroutineScope {
            locations.map { loc -> async { withTimeoutOrNull(5_000) { runCatching { client.describe(loc) }.getOrNull() } } }
                .awaitAll()
        }.filterNotNull().filter { !it.isSonos }.distinctBy { it.id }.sortedBy { it.friendlyName.lowercase() }
    }
}

class DlnaAdapter(private val discovery: DlnaDiscovery) : VendorAdapter {
    override val brand: Brand = Brand.UPNP
    override suspend fun discover(): List<SpeakerSummary> = discovery.discover().map { it.toSummary() }
}

/**
 * Standard UPnP AV RenderingControl + AVTransport. Works for most DLNA speakers and also for Sonos
 * players (which implement the same services under `/MediaRenderer/...`).
 */
class UpnpPlaybackControl(
    private val soap: SoapClient,
    private val renderingControl: ServiceEndpoint?,
    private val avTransport: ServiceEndpoint?,
    private val maxVolume: Int = DlnaClient.DEFAULT_MAX_VOLUME,
    /** Turns a link into the URI this platform wants (Sonos marks radio streams). */
    private val urlFor: (url: String, live: Boolean) -> String = { url, _ -> url },
) : PlaybackControl, UrlPlayback {

    override val capabilities: Set<Capability> = buildSet {
        if (renderingControl != null) { add(Capability.VOLUME); add(Capability.MUTE) }
        if (avTransport != null) add(Capability.TRANSPORT)
    }

    override suspend fun status(): PlaybackStatus = coroutineScope {
        val volume = async { rc("GetVolume", MASTER)?.get("CurrentVolume")?.trim()?.toIntOrNull() }
        val mute = async { rc("GetMute", MASTER)?.get("CurrentMute")?.trim()?.let { it == "1" || it.equals("true", true) } }
        val transport = async { avt("GetTransportInfo")?.get("CurrentTransportState")?.let(::parseTransport) }
        val track = async { avt("GetPositionInfo")?.get("TrackMetaData")?.let(Didl::parse) }
        val t = track.await()
        PlaybackStatus(
            volume = volume.await(),
            maxVolume = maxVolume,
            muted = mute.await(),
            transport = transport.await() ?: TransportState.UNKNOWN,
            title = t?.title,
            artist = t?.artist,
        )
    }

    /** Transport and track only: two requests instead of [status]'s four, for list views that poll. */
    suspend fun nowPlaying(): NowPlaying = coroutineScope {
        val transport = async { avt("GetTransportInfo")?.get("CurrentTransportState")?.let(::parseTransport) }
        val track = async { avt("GetPositionInfo")?.get("TrackMetaData")?.let(Didl::parse) }
        val t = track.await()
        NowPlaying(transport.await() ?: TransportState.UNKNOWN, t?.title, t?.artist)
    }

    override suspend fun setVolume(volume: Int) {
        call(renderingControl, "SetVolume", INSTANCE + MASTER + ("DesiredVolume" to volume.coerceIn(0, maxVolume).toString()))
    }

    override suspend fun setMuted(muted: Boolean) {
        call(renderingControl, "SetMute", INSTANCE + MASTER + ("DesiredMute" to if (muted) "1" else "0"))
    }

    override suspend fun playUrl(url: String, title: String, live: Boolean) {
        val uri = urlFor(url.trim(), live)
        call(avTransport, "SetAVTransportURI", INSTANCE + ("CurrentURI" to uri) + ("CurrentURIMetaData" to Didl.item(title, uri, live)))
        play()
    }

    override suspend fun play() { call(avTransport, "Play", INSTANCE + ("Speed" to "1")) }
    override suspend fun pause() { call(avTransport, "Pause", INSTANCE) }
    override suspend fun stop() { call(avTransport, "Stop", INSTANCE) }
    override suspend fun next() { call(avTransport, "Next", INSTANCE) }
    override suspend fun previous() { call(avTransport, "Previous", INSTANCE) }

    private suspend fun rc(action: String, extra: List<Pair<String, String>>) =
        renderingControl?.let { runCatching { call(it, action, INSTANCE + extra) }.getOrNull() }

    private suspend fun avt(action: String) =
        avTransport?.let { runCatching { call(it, action, INSTANCE) }.getOrNull() }

    private suspend fun call(service: ServiceEndpoint?, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val s = service ?: throw UnsupportedOperationException("This speaker doesn't support $action")
        return soap.call(SoapCall("", s.controlUrl, s.serviceType, action, args))
    }

    companion object {
        private val INSTANCE = listOf("InstanceID" to "0")
        private val MASTER = listOf("Channel" to "Master")

        fun parseTransport(value: String): TransportState = when (value.trim().uppercase()) {
            "PLAYING" -> TransportState.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> TransportState.PAUSED
            "STOPPED" -> TransportState.STOPPED
            "NO_MEDIA_PRESENT" -> TransportState.NO_MEDIA
            "TRANSITIONING" -> TransportState.TRANSITIONING
            else -> TransportState.UNKNOWN
        }
    }
}

data class NowPlaying(val transport: TransportState, val title: String?, val artist: String?) {
    /** "Playing · Song — Artist", or null when there's nothing worth showing. */
    val line: String?
        get() {
            val what = listOfNotNull(title, artist).joinToString(" — ").ifEmpty { null }
            return when (transport) {
                TransportState.PLAYING -> what?.let { "▶ $it" } ?: "▶ Playing"
                TransportState.PAUSED -> what?.let { "Paused · $it" } ?: "Paused"
                else -> null
            }
        }
}

/** Title/artist out of a DIDL-Lite `TrackMetaData` string. */
object Didl {
    data class Track(val title: String?, val artist: String?)

    /** Minimal DIDL-Lite for SetAVTransportURI, so renderers can show a title. */
    fun item(title: String, uri: String, live: Boolean): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${Xml.escape(title)}</dc:title>" +
            "<upnp:class>${if (live) "object.item.audioItem.audioBroadcast" else "object.item.audioItem.musicTrack"}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:*:*\">${Xml.escape(uri)}</res></item></DIDL-Lite>"

    fun parse(metadata: String): Track? {
        if (metadata.isBlank() || metadata == "NOT_IMPLEMENTED") return null
        val root = runCatching { Xml.parse(metadata) }.getOrNull() ?: return null
        val title = root.find("title")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val artist = (root.find("artist") ?: root.find("creator"))?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return if (title == null && artist == null) null else Track(title, artist)
    }
}
