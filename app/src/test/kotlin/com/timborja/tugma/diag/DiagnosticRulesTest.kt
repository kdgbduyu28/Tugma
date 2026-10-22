package com.timborja.tugma.diag

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.LogEntry
import com.timborja.tugma.upnp.SsdpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRulesTest {
    private fun resp(host: String, server: String?, usn: String? = null) =
        SsdpResponse("http://$host:1400/xml/device_description.xml", "upnp:rootdevice", usn, server, emptyMap())

    @Test
    fun ssdpSummaryCountsHostsAndSonos() {
        val s = SsdpSummary.of(
            listOf(
                resp("192.168.1.10", "Linux UPnP/1.0 Sonos/85.0 (ZPS9)"),
                resp("192.168.1.10", "Linux UPnP/1.0 Sonos/85.0 (ZPS9)"),
                resp("192.168.1.11", null, "uuid:RINCON_ABC::urn:schemas-upnp-org:device:ZonePlayer:1"),
                resp("192.168.1.20", "Router UPnP/1.0"),
            ),
        )
        assertEquals(4, s.responses)
        assertEquals(3, s.hosts)
        assertEquals(2, s.sonosHosts)
        assertEquals(2, s.servers.size)
    }

    @Test
    fun hintsExplainFailures() {
        assertEquals(CheckStatus.FAIL, DiagnosticRules.network(false).status)
        val none = DiagnosticRules.ssdp(SsdpSummary(0, 0, 0, emptyList()), 3000)
        assertEquals(CheckStatus.FAIL, none.status)
        assertTrue(none.hint!!.contains("multicast"))

        val sawSonos = SsdpSummary(3, 2, 2, emptyList())
        val warn = DiagnosticRules.source(Brand.SONOS, 0, null, sawSonos, 10)
        assertEquals(CheckStatus.WARN, warn.status)
        assertNotNull(warn.hint)
        assertEquals(CheckStatus.SKIP, DiagnosticRules.source(Brand.CAST, 0, null, sawSonos, 10).status)
        assertEquals(CheckStatus.PASS, DiagnosticRules.source(Brand.LINKPLAY, 2, null, null, 10).status)
        assertEquals(CheckStatus.FAIL, DiagnosticRules.source(Brand.SONOS, 0, "No speakers found.", null, 10).status)
    }

    @Test
    fun reportListsChecksAndFailedUrlsButNoBodies() {
        val log = listOf(
            LogEntry(1, 0, "POST", "http://192.168.1.10:1400/ZoneGroupTopology/Control", emptyMap(), "<secret body/>", 500, "<fault/>", 12, null),
            LogEntry(2, 0, "GET", "http://192.168.1.10:1400/ok", emptyMap(), "", 200, "", 5, null),
        )
        val text = DiagnosticRules.report(listOf("App" to "1.0"), listOf(DiagnosticRules.network(true)), log, listOf("Sonos/85.0"))
        assertTrue(text.contains("[OK] Network"))
        assertTrue(text.contains("ZoneGroupTopology/Control → 500"))
        assertFalse(text.contains("/ok"))
        assertFalse(text.contains("secret"))
    }
}
