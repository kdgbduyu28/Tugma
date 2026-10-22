package com.timborja.tugma.upnp.demo

import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpResponse
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.SsdpResponse
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.dlna.UpnpServices
import com.timborja.tugma.upnp.find
import kotlinx.coroutines.delay
import java.net.URI

/** SOAP response builders and request parsing shared by the fake devices. */
object FakeSoap {
    fun action(request: HttpRequest): String =
        request.headers.entries.firstOrNull { it.key.equals("SOAPACTION", true) }?.value.orEmpty().trim('"').substringAfter('#')

    fun args(request: HttpRequest): (String) -> String {
        val body = runCatching { Xml.parse(request.body.orEmpty()) }.getOrNull()
        return { name -> body?.find(name)?.textContent.orEmpty() }
    }

    fun ok(service: String, action: String, out: Map<String, String> = emptyMap()): HttpResponse = HttpResponse(
        200,
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"$service\">" +
            out.entries.joinToString("") { "<${it.key}>${Xml.escape(it.value)}</${it.key}>" } +
            "</u:${action}Response></s:Body></s:Envelope>",
    )

    fun fault(code: Int): HttpResponse = HttpResponse(
        500,
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>",
    )
}

/** In-memory RenderingControl + AVTransport state for one fake player. */
class FakeRenderer(
    var volume: Int = 20,
    val maxVolume: Int = 100,
    var muted: Boolean = false,
    var transport: String = "STOPPED",
    var title: String? = "Tugma demo track",
    var artist: String? = "Demo Artist",
) {
    val rcService = UpnpServices.RENDERING_CONTROL + "1"
    val avtService = UpnpServices.AV_TRANSPORT + "1"

    fun renderingControl(action: String, arg: (String) -> String): HttpResponse = when (action) {
        "GetVolume" -> FakeSoap.ok(rcService, action, mapOf("CurrentVolume" to volume.toString()))
        "SetVolume" -> arg("DesiredVolume").toIntOrNull()?.takeIf { it in 0..maxVolume }
            ?.let { volume = it; FakeSoap.ok(rcService, action) } ?: FakeSoap.fault(402)
        "GetMute" -> FakeSoap.ok(rcService, action, mapOf("CurrentMute" to if (muted) "1" else "0"))
        "SetMute" -> { muted = arg("DesiredMute") == "1"; FakeSoap.ok(rcService, action) }
        else -> FakeSoap.fault(401)
    }

    /** Last URI set with SetAVTransportURI. */
    var uri: String? = null

    fun avTransport(action: String, arg: (String) -> String = { "" }): HttpResponse = when (action) {
        "SetAVTransportURI" -> {
            uri = arg("CurrentURI")
            com.timborja.tugma.upnp.dlna.Didl.parse(arg("CurrentURIMetaData")).let { t -> title = t?.title; artist = t?.artist }
            transport = "STOPPED"
            FakeSoap.ok(avtService, action)
        }
        "GetTransportInfo" -> FakeSoap.ok(
            avtService, action,
            mapOf("CurrentTransportState" to transport, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1"),
        )
        "GetPositionInfo" -> FakeSoap.ok(avtService, action, mapOf("Track" to "1", "TrackMetaData" to didl()))
        "Play" -> { transport = "PLAYING"; FakeSoap.ok(avtService, action) }
        "Pause" -> { transport = "PAUSED_PLAYBACK"; FakeSoap.ok(avtService, action) }
        "Stop" -> { transport = "STOPPED"; FakeSoap.ok(avtService, action) }
        "Next", "Previous" -> FakeSoap.ok(avtService, action)
        else -> FakeSoap.fault(401)
    }

    private fun didl(): String {
        if (title == null && artist == null) return ""
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item id=\"-1\" parentID=\"-1\" restricted=\"1\">" +
            (title?.let { "<dc:title>${Xml.escape(it)}</dc:title>" } ?: "") +
            (artist?.let { "<dc:creator>${Xml.escape(it)}</dc:creator>" } ?: "") +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>"
    }
}

/**
 * Generic DLNA renderers for demo mode and tests. Brand-neutral on purpose: real makers' names
 * stay out of fake devices.
 */
class FakeDlnaHousehold(
    devices: List<Device> = DEFAULT_DEVICES,
    private val latencyMillis: Long = 0,
) : HttpTransport, Discoverer {

    data class Device(
        val udn: String,
        val name: String,
        val manufacturer: String,
        val model: String,
        val host: String,
        val port: Int = 49152,
        val maxVolume: Int = 100,
        val hasRenderingControl: Boolean = true,
        /** Put the MediaRenderer in a `<deviceList>` and give it a `<URLBase>`, as some devices do. */
        val embedded: Boolean = false,
    )

    private val devices = devices.toList()
    val renderers: Map<String, FakeRenderer> = devices.associate { it.udn to FakeRenderer(volume = it.maxVolume / 4, maxVolume = it.maxVolume) }
    val receivedActions = mutableListOf<String>()

    override suspend fun search(): List<SsdpResponse> {
        if (latencyMillis > 0) delay(latencyMillis * 3)
        return devices.map {
            SsdpResponse("http://${it.host}:${it.port}/description.xml", UpnpServices.MEDIA_RENDERER_ST, "uuid:${it.udn}", "Linux UPnP/1.0 Demo/1.0", emptyMap())
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (latencyMillis > 0) delay(latencyMillis)
        val uri = URI(request.url)
        val d = devices.firstOrNull { it.host == uri.host && it.port == uri.port }
            ?: throw java.net.ConnectException("No route to ${uri.host}")
        val r = renderers.getValue(d.udn)
        val prefix = if (d.embedded) "/dev/mr" else ""
        return synchronized(this) {
            when {
                request.method == "GET" && uri.path == "/description.xml" -> HttpResponse(200, description(d))
                request.method == "GET" && uri.path == "$prefix/rcs.xml" -> HttpResponse(200, rcScpd(d.maxVolume))
                request.method == "POST" && uri.path == "$prefix/upnp/control/rendercontrol1" && d.hasRenderingControl -> {
                    val action = FakeSoap.action(request).also { receivedActions += it }
                    r.renderingControl(action, FakeSoap.args(request))
                }
                request.method == "POST" && uri.path == "$prefix/upnp/control/avtransport1" -> {
                    val action = FakeSoap.action(request).also { receivedActions += it }
                    r.avTransport(action, FakeSoap.args(request))
                }
                else -> HttpResponse(404, "")
            }
        }
    }

    private fun description(d: Device): String {
        val rc = if (d.hasRenderingControl) """
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
              <controlURL>upnp/control/rendercontrol1</controlURL><eventSubURL>upnp/event/rendercontrol1</eventSubURL><SCPDURL>rcs.xml</SCPDURL></service>""" else ""
        val services = """<serviceList>$rc
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
              <controlURL>upnp/control/avtransport1</controlURL><SCPDURL>avts.xml</SCPDURL></service>
          </serviceList>"""
        val renderer = """<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <friendlyName>${d.name}</friendlyName><manufacturer>${d.manufacturer}</manufacturer>
          <modelName>${d.model}</modelName><UDN>uuid:${d.udn}</UDN>$services"""
        val body = if (d.embedded) {
            """<URLBase>http://${d.host}:${d.port}/dev/mr/</URLBase>
            <device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>${d.name} hub</friendlyName>
              <UDN>uuid:${d.udn}-root</UDN><deviceList><device>$renderer</device></deviceList></device>"""
        } else {
            "<device>$renderer</device>"
        }
        // Without URLBase, relative control URLs resolve against /description.xml, i.e. the root path.
        return """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>$body</root>"""
    }

    private fun rcScpd(max: Int) = """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>
        <action><name>GetVolume</name></action><action><name>SetVolume</name></action></actionList>
        <serviceStateTable><stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType>
        <allowedValueRange><minimum>0</minimum><maximum>$max</maximum><step>1</step></allowedValueRange></stateVariable></serviceStateTable></scpd>"""

    companion object {
        val DEFAULT_DEVICES = listOf(
            Device("demo-dlna-0001", "Sala Speaker", "Demo Audio Co.", "WS-100", "192.168.50.31"),
            Device("demo-dlna-0002", "Garahe Soundbar", "Demo Audio Co.", "SB-2", "192.168.50.32", maxVolume = 50, embedded = true),
            Device("demo-dlna-0003", "Terrace Receiver", "Example AV", "RX-7", "192.168.50.33", maxVolume = 30),
            Device("demo-dlna-0004", "Kubo Streamer", "Example AV", "ST-1", "192.168.50.34", hasRenderingControl = false),
        )
    }
}
