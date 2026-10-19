package com.timborja.tugma.upnp

import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.upnp.demo.FakeDlnaHousehold
import com.timborja.tugma.upnp.dlna.Didl
import com.timborja.tugma.upnp.dlna.DlnaClient
import com.timborja.tugma.upnp.dlna.DlnaDiscovery
import com.timborja.tugma.upnp.dlna.UpnpPlaybackControl
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTest {
    private val fake = FakeDlnaHousehold()
    private val client = DlnaClient(fake)
    private val discovery = DlnaDiscovery(client, fake)

    @Test
    fun `discovers and describes every renderer`() = runTest {
        val all = discovery.discover()
        assertEquals(listOf("Garahe Soundbar", "Kubo Streamer", "Sala Speaker", "Terrace Receiver"), all.map { it.friendlyName })
        val sala = all.first { it.friendlyName == "Sala Speaker" }
        assertEquals("http://192.168.50.31:49152/upnp/control/rendercontrol1", sala.renderingControl?.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:RenderingControl:1", sala.renderingControl?.serviceType)
        assertEquals(100, sala.maxVolume)
        assertEquals(setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT), sala.capabilities)
        assertEquals("Demo Audio Co.", sala.toSummary().manufacturer)
    }

    @Test
    fun `embedded renderer resolves against URLBase and reads volume range`() = runTest {
        val bar = discovery.discover().first { it.friendlyName == "Garahe Soundbar" }
        assertEquals("demo-dlna-0002", bar.id)
        assertEquals("http://192.168.50.32:49152/dev/mr/upnp/control/avtransport1", bar.avTransport?.controlUrl)
        assertEquals(50, bar.maxVolume)
    }

    @Test
    fun `renderer without RenderingControl only offers transport`() = runTest {
        val kubo = discovery.discover().first { it.friendlyName == "Kubo Streamer" }
        assertEquals(setOf(Capability.TRANSPORT), kubo.capabilities)
        val control = client.playback(kubo)
        val status = control.status()
        assertNull(status.volume)
        assertFailsWith<UnsupportedOperationException> { control.setVolume(10) }
    }

    @Test
    fun `volume mute and transport round trip`() = runTest {
        val rx = discovery.discover().first { it.friendlyName == "Terrace Receiver" }
        val control = client.playback(rx)
        var s = control.status()
        assertEquals(7, s.volume)
        assertEquals(30, s.maxVolume)
        assertEquals(false, s.muted)
        assertEquals(TransportState.STOPPED, s.transport)
        assertEquals("Tugma demo track", s.title)
        assertEquals("Demo Artist", s.artist)

        control.setVolume(99) // clamped to the device's maximum
        control.setMuted(true)
        control.play()
        s = control.status()
        assertEquals(30, s.volume)
        assertEquals(true, s.muted)
        assertEquals(TransportState.PLAYING, s.transport)
        control.pause()
        assertEquals(TransportState.PAUSED, control.status().transport)
    }

    @Test
    fun `sonos players answering as renderers are skipped`() = runTest {
        val sonosLike = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType><UDN>uuid:RINCON_ABC01400</UDN>
            <deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <UDN>uuid:RINCON_ABC01400_MR</UDN><serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <controlURL>/MediaRenderer/RenderingControl/Control</controlURL><SCPDURL>/xml/RenderingControl1.xml</SCPDURL>
            </service></serviceList></device></deviceList></device></root>"""
        val transport = HttpTransport { r ->
            if (r.url.endsWith("device_description.xml")) HttpResponse(200, sonosLike) else HttpResponse(404, "")
        }
        val discoverer = object : Discoverer {
            override suspend fun search() = listOf(SsdpResponse("http://192.168.1.20:1400/xml/device_description.xml", null, null, null, emptyMap()))
        }
        val renderer = DlnaClient(transport).describe("http://192.168.1.20:1400/xml/device_description.xml")!!
        assertTrue(renderer.isSonos)
        assertEquals("http://192.168.1.20:1400/MediaRenderer/RenderingControl/Control", renderer.renderingControl?.controlUrl)
        assertEquals(100, renderer.maxVolume, "falls back when the SCPD can't be read")
        assertTrue(DlnaDiscovery(DlnaClient(transport), discoverer).discover().isEmpty())
    }

    @Test
    fun `didl and transport parsing`() {
        val t = Didl.parse("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item><dc:title>Buwan</dc:title><upnp:artist>Juan Karlos</upnp:artist></item></DIDL-Lite>")!!
        assertEquals("Buwan", t.title)
        assertEquals("Juan Karlos", t.artist)
        assertNull(Didl.parse(""))
        assertNull(Didl.parse("NOT_IMPLEMENTED"))
        assertNull(Didl.parse("not xml"))
        assertEquals(TransportState.PAUSED, UpnpPlaybackControl.parseTransport("PAUSED_PLAYBACK"))
        assertEquals(TransportState.NO_MEDIA, UpnpPlaybackControl.parseTransport("NO_MEDIA_PRESENT"))
        assertEquals(TransportState.UNKNOWN, UpnpPlaybackControl.parseTransport("CUSTOM"))
    }

    @Test
    fun `scpd volume range`() {
        val xml = "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><serviceStateTable><stateVariable><name>Volume</name>" +
            "<allowedValueRange><minimum>0</minimum><maximum>60</maximum></allowedValueRange></stateVariable></serviceStateTable></scpd>"
        assertEquals(0..60, Scpd.allowedRange(xml, "Volume"))
        assertNull(Scpd.allowedRange(xml, "Mute"))
        assertFalse(Scpd.allowedRange(xml, "Volume")!!.isEmpty())
    }
}
