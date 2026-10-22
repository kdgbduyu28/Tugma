package com.timborja.tugma.data

import com.timborja.tugma.core.Station
import com.timborja.tugma.eq.EqLibrary
import com.timborja.tugma.eq.EqPresets
import com.timborja.tugma.sonos.SonosScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val full = Backup(
        eq = EqLibrary(profiles = listOf(EqPresets.FLAT.copy(id = "user:1", name = "Mine")), assignments = mapOf("bt:AA" to "user:1")),
        stations = listOf(Station("Example FM", "https://radio.example/live")),
        scenes = listOf(SonosScene("s", "Morning", "RINCON_A", listOf("RINCON_B"), mapOf("RINCON_A" to 20))),
        manualHosts = setOf("192.168.1.20", "192.168.1.21:1400"),
    )

    @Test
    fun roundTrips() {
        val text = BackupCodec.encode(full, 123)
        assertTrue(text.contains("\"format\": \"tugma-backup\""))
        assertEquals(full, BackupCodec.decode(text))
        assertEquals("1 EQ profiles, 1 links, 1 scenes, 2 speakers added by IP", full.summary)
    }

    @Test
    fun hiddenSpeakersRoundTripAndBadKeysDrop() {
        val b = BackupCodec.decode(BackupCodec.encode(Backup(hiddenSpeakers = setOf("UPNP|tv-1", "SONOS|RINCON_A")), 0))
        assertEquals(setOf("UPNP|tv-1", "SONOS|RINCON_A"), b.hiddenSpeakers)
        val bad = BackupCodec.decode("""{"format":"tugma-backup","version":1,"hiddenSpeakers":["nobar","CAST|x"]}""")
        assertEquals(setOf("CAST|x"), bad.hiddenSpeakers)
    }

    @Test
    fun nicknamesRoundTrip() {
        val b = BackupCodec.decode(BackupCodec.encode(Backup(nicknames = mapOf("CAST|ip:1" to "Kitchen Nest")), 0))
        assertEquals(mapOf("CAST|ip:1" to "Kitchen Nest"), b.nicknames)
    }

    @Test
    fun missingSectionsStayNull() {
        val b = BackupCodec.decode("""{"format":"tugma-backup","version":1,"stations":[]}""")
        assertNull(b.eq)
        assertNull(b.scenes)
        assertEquals(emptyList<Station>(), b.stations)
    }

    @Test
    fun badLinksAreDropped() {
        val b = BackupCodec.decode("""{"format":"tugma-backup","version":1,"stations":[{"name":"x","url":"javascript:alert(1)"},{"name":"ok","url":"http://a.example/s"}]}""")
        assertEquals(listOf("ok"), b.stations?.map { it.name })
    }

    @Test
    fun rejectsOtherFiles() {
        for (bad in listOf("hello", """{"format":"other"}""", """{"format":"tugma-backup","version":99}""", """{"format":"tugma-backup","version":1,"scenes":"nope"}""")) {
            try {
                BackupCodec.decode(bad)
                fail("accepted $bad")
            } catch (e: BackupFormatException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }
}
