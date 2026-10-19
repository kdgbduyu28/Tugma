package com.timborja.tugma.upnp

import com.timborja.tugma.core.InMemoryCommandLog
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoapEnvelopeTest {
    @Test
    fun `envelope matches pair-sonos byte for byte`() {
        // erikmansson/pair-sonos pair.py pair_payload_format, formatted with two UUIDs.
        val pairSonos = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:AddBondedZones xmlns:u=\"urn:schemas-upnp-org:service:DeviceProperties:1\">" +
            "<ChannelMapSet>RINCON_A:LF,LF;RINCON_B:RF,RF</ChannelMapSet>" +
            "</u:AddBondedZones>" +
            "</s:Body>" +
            "</s:Envelope>"
        val ours = SoapEnvelope.build(
            "urn:schemas-upnp-org:service:DeviceProperties:1",
            "AddBondedZones",
            listOf("ChannelMapSet" to "RINCON_A:LF,LF;RINCON_B:RF,RF"),
        )
        assertEquals(pairSonos, ours)
    }

    @Test
    fun `arguments are xml-escaped`() {
        val env = SoapEnvelope.build("urn:x", "Do", listOf("A" to "<&\">"))
        assertTrue("<A>&lt;&amp;&quot;&gt;</A>" in env)
    }

    @Test
    fun `soapaction header and url`() {
        val call = SoapCall("http://192.168.1.20:1400/", "/DeviceProperties/Control", "urn:schemas-upnp-org:service:DeviceProperties:1", "AddHTSatellite")
        val req = call.toHttpRequest()
        assertEquals("http://192.168.1.20:1400/DeviceProperties/Control", req.url)
        assertEquals("\"urn:schemas-upnp-org:service:DeviceProperties:1#AddHTSatellite\"", req.headers["SOAPACTION"])
    }

    @Test
    fun `response output args are unescaped`() {
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetZoneGroupStateResponse xmlns:u=\"urn:schemas-upnp-org:service:ZoneGroupTopology:1\">" +
            "<ZoneGroupState>&lt;ZoneGroupState&gt;&lt;ZoneGroups/&gt;&lt;/ZoneGroupState&gt;</ZoneGroupState>" +
            "</u:GetZoneGroupStateResponse></s:Body></s:Envelope>"
        assertEquals("<ZoneGroupState><ZoneGroups/></ZoneGroupState>", SoapEnvelope.parseResponse(body)["ZoneGroupState"])
    }

    @Test
    fun `fault carries upnp error code`() {
        val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
            "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>401</errorCode></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"
        val fault = SoapEnvelope.parseFault(500, body)
        assertEquals(401, fault.upnpErrorCode)
        assertTrue("UPnP error 401" in fault.message!!)
    }
}

class SsdpTest {
    @Test
    fun `m-search format`() {
        val msg = SsdpDiscoverer.mSearch("urn:schemas-upnp-org:device:ZonePlayer:1", 1)
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue("MAN: \"ssdp:discover\"\r\n" in msg)
        assertTrue("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" in msg)
        assertTrue(msg.endsWith("\r\n\r\n"))
    }

    @Test
    fun `parses search reply`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nEXT:\r\n" +
            "LOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\n" +
            "SERVER: Linux UPnP/1.0 Sonos/85.0-65020 (ZPS19)\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
            "USN: uuid:RINCON_BAR000000001400::urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        val r = SsdpDiscoverer.parseResponse(reply)!!
        assertEquals("192.168.1.20", r.host)
        assertEquals(1400, r.port)
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", r.st)
    }

    @Test
    fun `ignores notify and replies without location`() {
        assertNull(SsdpDiscoverer.parseResponse("NOTIFY * HTTP/1.1\r\nLOCATION: http://x/\r\n\r\n"))
        assertNull(SsdpDiscoverer.parseResponse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }
}

class LanAddressTest {
    @Test
    fun `private ranges are local`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.255", "169.254.3.4", "127.0.0.1", "sonos.local", "[fe80::1]")
            .forEach { assertTrue(LanAddress.isLocal(it), it) }
    }

    @Test
    fun `public addresses are not`() {
        listOf("8.8.8.8", "172.32.0.1", "example.com", "193.168.1.1", "[2001:4860::8888]", "999.1.1.1")
            .forEach { assertFalse(LanAddress.isLocal(it), it) }
    }

    @Test
    fun `guard refuses public hosts`() = runTest {
        val guard = LanOnlyTransport { HttpResponse(200, "") }
        assertFailsWith<NotLocalNetworkException> { guard.execute(HttpRequest("GET", "http://example.com/")) }
        assertEquals(200, guard.execute(HttpRequest("GET", "http://192.168.1.2:1400/")).status)
    }
}

class XmlAndDescriptionTest {
    @Test
    fun `doctype is refused`() {
        assertFailsWith<Exception> {
            Xml.parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>")
        }
    }

    @Test
    fun `scpd action names`() {
        val xml = "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><actionList>" +
            "<action><name>AddBondedZones</name></action><action><name>AddHTSatellite</name></action></actionList></scpd>"
        assertEquals(setOf("AddBondedZones", "AddHTSatellite"), Scpd.actionNames(xml))
    }
}

class LoggingTransportTest {
    @Test
    fun `logs success and failure`() = runTest {
        val log = InMemoryCommandLog()
        val ok = LoggingTransport({ HttpResponse(200, "pong") }, log, clock = { 1000L })
        ok.execute(HttpRequest("POST", "http://192.168.1.2/x", mapOf("A" to "b"), "ping"))
        val bad = LoggingTransport({ throw IOException("boom") }, log, clock = { 2000L })
        assertFailsWith<IOException> { bad.execute(HttpRequest("GET", "http://192.168.1.3/")) }
        val entries = log.entries.value
        assertEquals(2, entries.size)
        assertEquals("boom", entries[0].error)
        assertEquals("pong", entries[1].responseBody)
        assertEquals("ping", entries[1].requestBody)
    }
}
