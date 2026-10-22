package com.timborja.tugma.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogEntryTest {
    private fun entry(method: String, body: String = "") =
        LogEntry(1, 0, method, "http://192.168.1.10:1400/x", mapOf("SOAPACTION" to "\"urn:a#B\""), body, 200, null, 1)

    @Test
    fun `curl quotes headers and bodies safely`() {
        assertEquals(
            "curl -sS -X POST -H 'SOAPACTION: \"urn:a#B\"' --data '<a>it'\\''s</a>' 'http://192.168.1.10:1400/x'",
            entry("POST", "<a>it's</a>").toCurl(),
        )
        assertEquals("curl -sS -H 'SOAPACTION: \"urn:a#B\"' 'http://192.168.1.10:1400/x'", entry("GET").toCurl())
        assertNull(entry("CAST").toCurl())
    }
}
