package com.timborja.tugma.upnp

import com.timborja.tugma.upnp.gena.GenaClient
import com.timborja.tugma.upnp.gena.GenaParser
import com.timborja.tugma.upnp.gena.GenaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenaTest {
    private val avtLastChange = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">""" +
        """<TransportState val="PLAYING"/><CurrentTrackMetaData val="&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;&lt;item&gt;&lt;dc:title&gt;Song&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"/>""" +
        "</InstanceID></Event>"

    @Test
    fun `parses LastChange for AVTransport and RenderingControl`() {
        val avt = GenaParser.lastChange(avtLastChange)
        assertEquals("PLAYING", avt["TransportState"])
        assertTrue(avt.getValue("CurrentTrackMetaData").contains("<dc:title>Song</dc:title>"))
        val rcs = GenaParser.lastChange(
            """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0"><Volume channel="Master" val="31"/><Volume channel="LF" val="100"/><Mute channel="Master" val="0"/></InstanceID></Event>""",
        )
        assertEquals("31", rcs["Volume"])
        assertEquals("0", rcs["Mute"])
        assertTrue(GenaParser.lastChange("not xml").isEmpty())
    }

    @Test
    fun `subscribe reads SID and timeout, renew and unsubscribe send the SID`() = runBlocking {
        val seen = mutableListOf<HttpRequest>()
        val client = GenaClient { r ->
            seen += r
            HttpResponse(200, "", mapOf("sid" to "uuid:sub-1", "Timeout" to "Second-1800"))
        }
        val s = client.subscribe("http://192.168.1.10:1400/MediaRenderer/AVTransport/Event", "http://192.168.1.5:41234/notify")
        assertEquals("uuid:sub-1", s.sid)
        assertEquals(1800, s.timeoutSeconds)
        assertEquals("<http://192.168.1.5:41234/notify>", seen[0].headers["CALLBACK"])
        assertEquals("SUBSCRIBE", seen[0].method)
        client.renew(s)
        assertEquals("uuid:sub-1", seen[1].headers["SID"])
        client.unsubscribe(s)
        assertEquals("UNSUBSCRIBE", seen[2].method)
    }

    @Test
    fun `server turns a NOTIFY into an event and rejects other requests`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GenaServer(InetAddress.getLoopbackAddress())
        server.start(scope)
        try {
            // UNDISPATCHED: subscribe before any NOTIFY is sent, so the event can't be missed.
            val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { server.events.first() } }
            val body = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>${Xml.escape(avtLastChange)}</LastChange></e:property></e:propertyset>"
            fun send(text: String): String = Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.getOutputStream().write(text.toByteArray())
                s.getInputStream().bufferedReader().readLine()
            }
            assertTrue(send("GET / HTTP/1.1\r\nHost: x\r\n\r\n").contains("400"))
            val bytes = body.toByteArray()
            val status = send("NOTIFY /notify HTTP/1.1\r\nHOST: x\r\nCONTENT-TYPE: text/xml\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: uuid:sub-1\r\nSEQ: 4\r\nCONTENT-LENGTH: ${bytes.size}\r\n\r\n$body")
            assertTrue(status.contains("200"))
            val e = first.await()
            assertEquals("uuid:sub-1", e.sid)
            assertEquals(4, e.seq)
            assertEquals("PLAYING", GenaParser.lastChange(e.properties.getValue("LastChange"))["TransportState"])
            assertTrue(server.callbackUrl("192.168.1.5").startsWith("http://192.168.1.5:"))
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}

class EventUrlTest {
    @Test
    fun `renderers expose resolved event URLs when the description declares them`() = runBlocking {
        val fake = com.timborja.tugma.upnp.demo.FakeDlnaHousehold()
        val all = com.timborja.tugma.upnp.dlna.DlnaDiscovery(com.timborja.tugma.upnp.dlna.DlnaClient(fake), fake).discover()
        val sala = all.first { it.friendlyName == "Sala Speaker" }
        assertEquals("http://192.168.50.31:49152/upnp/event/rendercontrol1", sala.renderingControl?.eventUrl)
        assertEquals(null, sala.avTransport?.eventUrl)
    }
}
