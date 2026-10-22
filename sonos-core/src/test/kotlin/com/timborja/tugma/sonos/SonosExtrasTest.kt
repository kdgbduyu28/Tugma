package com.timborja.tugma.sonos

import com.timborja.tugma.core.TransportState
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.SoapFault
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonosExtrasTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun room(name: String) = household.load(household.discover()).speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `recurrence round-trips days and labels`() {
        assertEquals(Recurrence.WEEKDAYS, Recurrence.of((1..5).toSet()))
        assertEquals(Recurrence.DAILY, Recurrence.of((0..6).toSet()))
        assertEquals("ON_135", Recurrence.of(setOf(5, 1, 3)))
        assertEquals(setOf(1, 3, 5), Recurrence.days("ON_135"))
        assertEquals("Mon, Wed, Fri", Recurrence.label("ON_135"))
        assertTrue(Recurrence.isValid("ON_0123456"))
        assertFalse(Recurrence.isValid("ON_7"))
    }

    @Test
    fun `alarms can be created, listed, updated and deleted`() = runTest {
        val kitchen = room("Kitchen")
        val alarms = client.alarms(kitchen.endpoint)
        assertTrue(alarms.list().isEmpty())

        val id = alarms.create(SonosAlarm(startTime = SonosAlarm.time(6, 45), recurrence = Recurrence.WEEKDAYS, roomUuid = kitchen.uuid, volume = 15))
        val listed = alarms.list().single()
        assertEquals(id, listed.id)
        assertEquals("06:45", listed.shortTime)
        assertTrue(listed.isChime)
        assertEquals(15, listed.volume)

        alarms.update(listed.copy(enabled = false, startTime = SonosAlarm.time(7, 0)))
        assertEquals(false, alarms.list().single().enabled)
        assertEquals("07:00:00", alarms.list().single().startTime)

        alarms.delete(id)
        assertTrue(alarms.list().isEmpty())
        assertFailsWith<SoapFault> { alarms.delete(id) }
    }

    @Test
    fun `bad alarms are rejected before sending`() = runTest {
        val kitchen = room("Kitchen")
        val alarms = client.alarms(kitchen.endpoint)
        assertFailsWith<IllegalArgumentException> { alarms.create(SonosAlarm(startTime = "25:00:00", roomUuid = kitchen.uuid)) }
        assertFailsWith<IllegalArgumentException> { alarms.create(SonosAlarm(startTime = "07:00:00", recurrence = "SOMETIMES", roomUuid = kitchen.uuid)) }
        assertTrue(fake.receivedActions.none { it == "CreateAlarm" })
    }

    @Test
    fun `favorites parse with their metadata`() = runTest {
        val favs = client.favorites(room("Kitchen").endpoint).list()
        assertEquals(listOf("Morning Radio", "Focus Playlist", "Rainy Day Jazz"), favs.map { it.title })
        assertFalse(favs[0].needsQueue)
        assertTrue(favs[1].needsQueue)
        assertTrue("<dc:title>Morning Radio</dc:title>" in favs[0].metadata)
        assertEquals("Radio station", favs[0].description)
    }

    @Test
    fun `a stream plays directly, a playlist goes through the queue`() = runTest {
        val kitchen = room("Kitchen")
        val favs = client.favorites(kitchen.endpoint).list()
        val player = client.player(kitchen.endpoint, kitchen.uuid)

        player.play(favs[0])
        assertEquals(favs[0].uri, fake.currentUri[kitchen.uuid])
        assertTrue(fake.queue(kitchen.uuid).isEmpty())
        val status = client.playback(kitchen.endpoint, kitchen.endpoint).status()
        assertEquals(TransportState.PLAYING, status.transport)
        assertEquals("Morning Radio", status.title)

        player.play(favs[1])
        assertEquals(3, fake.queue(kitchen.uuid).size)
        assertTrue(fake.queue(kitchen.uuid).all { it.startsWith(favs[1].uri) })
        assertEquals("x-rincon-queue:${kitchen.uuid}#0", fake.currentUri[kitchen.uuid])
        assertEquals("Focus Playlist, track 1", client.playback(kitchen.endpoint, kitchen.endpoint).status().title)
    }

    @Test
    fun `sleep timer sets, reads back and cancels`() = runTest {
        val kitchen = room("Kitchen")
        val timer = client.sleepTimer(kitchen.endpoint)
        assertNull(timer.remainingSeconds())
        timer.set(45)
        assertEquals(45 * 60, timer.remainingSeconds())
        assertTrue("ConfigureSleepTimer" in fake.receivedActions)
        timer.set(null)
        assertNull(timer.remainingSeconds())
        assertEquals("01:30:00", SonosSleepTimer.format(5400))
        assertNull(SonosSleepTimer.parse(""))
        assertNull(SonosSleepTimer.parse("00:00:00"))
    }
}
