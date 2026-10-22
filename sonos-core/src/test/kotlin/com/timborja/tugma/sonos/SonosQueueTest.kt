package com.timborja.tugma.sonos

import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.SoapFault
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonosQueueTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun room(name: String) = household.load(household.discover()).speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `queue lists, plays from a track, removes and clears`() = runTest {
        val kitchen = room("Kitchen")
        val favs = client.favorites(kitchen.endpoint).list()
        client.player(kitchen.endpoint, kitchen.uuid).play(favs.first { it.title == "Focus Playlist" })

        val queue = client.queue(kitchen.endpoint, kitchen.uuid)
        val items = queue.list()
        assertEquals(listOf(1, 2, 3), items.map { it.position })
        assertEquals("Focus Playlist, track 2", items[1].title)

        queue.playFrom(3)
        assertEquals("Focus Playlist, track 3", client.playback(kitchen.endpoint, kitchen.endpoint).status().title)
        assertTrue("Seek" in fake.receivedActions)

        queue.remove(1)
        assertEquals(listOf("Focus Playlist, track 2", "Focus Playlist, track 3"), queue.list().map { it.title })
        assertFailsWith<SoapFault> { queue.remove(9) }

        queue.clear()
        assertTrue(queue.list().isEmpty())
    }

    @Test
    fun `line-in is detected from AudioIn and plays on another room`() = runTest {
        val office = room("Office")
        val kitchen = room("Kitchen")
        assertTrue(office.hasLineIn)
        assertFalse(kitchen.hasLineIn)

        client.inputs(kitchen.endpoint).lineIn(office)
        assertEquals("x-rincon-stream:${office.uuid}", fake.currentUri[kitchen.uuid])
        val status = client.playback(kitchen.endpoint, kitchen.endpoint).status()
        assertEquals("Line-in", status.title)
        assertEquals("Office", status.artist)
        assertFailsWith<IllegalArgumentException> { client.inputs(kitchen.endpoint).lineIn(kitchen) }
    }

    @Test
    fun `soundbars switch to TV, other speakers can't`() = runTest {
        val bar = room("Living Room")
        client.inputs(bar.endpoint).tv(bar)
        assertEquals("x-sonos-htastream:${bar.uuid}:spdif", fake.currentUri[bar.uuid])
        assertEquals("TV", client.playback(bar.endpoint, bar.endpoint).status().title)
        assertFailsWith<IllegalArgumentException> { client.inputs(bar.endpoint).tv(room("Kitchen")) }
    }

    @Test
    fun `queue DIDL parses ids and blanks`() {
        val items = SonosQueue.parse(
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                "<item id=\"Q:0/7\"><dc:title>Song</dc:title><dc:creator>Band</dc:creator></item><item id=\"x\"><dc:title></dc:title></item></DIDL-Lite>",
        )
        assertEquals(7, items[0].position)
        assertEquals("Band", items[0].artist)
        assertEquals("Track 2", items[1].title)
    }
}

class SonosPlayUrlTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    @Test
    fun `http radio becomes x-rincon-mp3radio, files and https stay as they are`() = runTest {
        assertEquals("x-rincon-mp3radio://radio.example/stream", SonosClient.sonosUri("http://radio.example/stream", live = true))
        assertEquals("http://files.example/a.mp3", SonosClient.sonosUri("http://files.example/a.mp3", live = false))
        assertEquals("https://radio.example/s", SonosClient.sonosUri("https://radio.example/s", live = true))

        val h = household.load(household.discover())
        val kitchen = h.speakers.first { it.roomName == "Kitchen" }
        val control = client.playback(kitchen.endpoint, kitchen.endpoint)
        control.playUrl("http://radio.example/stream", "Example FM")
        assertEquals("x-rincon-mp3radio://radio.example/stream", fake.currentUri[kitchen.uuid])
        assertEquals("Example FM", control.status().title)
    }
}

class SonosSettingsTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    @Test
    fun `status light, touch lock and Trueplay read and toggle`() = runTest {
        val h = household.load(household.discover())
        val kitchen = h.speakers.first { it.roomName == "Kitchen" }
        val settings = client.settings(kitchen.endpoint)
        val before = settings.read()
        assertEquals(true, before.statusLight)
        assertEquals(false, before.buttonsLocked)
        assertEquals(true, before.trueplayEnabled)
        assertEquals(true, before.trueplayAvailable)
        assertEquals("Kitchen", before.roomName)

        settings.setStatusLight(false)
        settings.setButtonsLocked(true)
        settings.setTrueplay(false)
        val after = settings.read()
        assertEquals(false, after.statusLight)
        assertEquals(true, after.buttonsLocked)
        assertEquals(false, after.trueplayEnabled)

        val study = h.speakers.first { it.roomName == "Study" }
        assertEquals(false, client.settings(study.endpoint).read().trueplayAvailable)
    }

    @Test
    fun `rename keeps the icon and shows up in topology`() = runTest {
        var h = household.load(household.discover())
        val loft = h.speakers.first { it.roomName == "Loft" && !it.invisible }
        client.settings(loft.endpoint).rename("  Attic  ")
        assertEquals("x-rincon-roomicon:living", fake.lastZoneIcon)
        h = household.refresh(h)
        // Both halves of the pair carry the new room name.
        assertEquals(2, h.speakers.count { it.roomName == "Attic" })
        assertFailsWith<IllegalArgumentException> { client.settings(loft.endpoint).rename(" ") }
    }
}
