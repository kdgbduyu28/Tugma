package com.timborja.tugma.sonos

import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonosPlaybackTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    @Test
    fun `volume goes to the player, transport to its coordinator`() = runTest {
        val s = household.load(household.discover())
        val loftLeft = s.speakers.first { it.roomName == "Loft" && !it.invisible }
        val loftRight = s.speakers.first { it.roomName == "Loft" && it.invisible }
        assertEquals(loftLeft.uuid, s.coordinatorOf(loftRight.uuid)?.uuid)

        val control = client.playback(loftLeft.endpoint, s.coordinatorOf(loftLeft.uuid)!!.endpoint)
        control.setVolume(42)
        control.play()
        val status = control.status()
        assertEquals(42, status.volume)
        assertEquals(TransportState.PLAYING, status.transport)
        assertEquals(42, fake.renderers.getValue(loftLeft.uuid).volume)
        assertEquals("PLAYING", fake.renderers.getValue(loftLeft.uuid).transport)
    }

    @Test
    fun `hidden bond members don't offer playback`() = runTest {
        val s = household.load(household.discover())
        val right = s.speakers.first { it.invisible }
        assertFalse(Capability.VOLUME in right.capabilities)
        assertTrue(right.toSummary().hidden)
        val kitchen = s.speakers.first { it.roomName == "Kitchen" }
        assertTrue(setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT).all { it in kitchen.capabilities })
        assertEquals("Sonos", kitchen.toSummary().manufacturer)
    }
}
