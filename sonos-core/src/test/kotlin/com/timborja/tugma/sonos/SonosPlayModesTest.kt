package com.timborja.tugma.sonos

import com.timborja.tugma.core.RepeatMode
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.SoapFault
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonosPlayModesTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun room(name: String) = household.load(household.discover()).speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `play mode strings round trip, as SoCo names them`() {
        val all = RepeatMode.entries.flatMap { r -> listOf(true, false).map { PlayMode(it, r) } }
        assertEquals(SonosAlarm.PLAY_MODES.toSet(), all.map { it.wire }.toSet())
        all.forEach { assertEquals(it, PlayMode.parse(it.wire)) }
        assertEquals(PlayMode(true, RepeatMode.ALL), PlayMode.parse(" shuffle "))
        assertNull(PlayMode.parse("RANDOM"))
    }

    @Test
    fun `times parse and format`() {
        assertEquals(3725, SonosTransportSettings.parseTime("1:02:05"))
        assertEquals(65, SonosTransportSettings.parseTime("0:01:05.250"))
        assertNull(SonosTransportSettings.parseTime("NOT_IMPLEMENTED"))
        assertEquals("0:03:35", SonosTransportSettings.formatTime(215))
        assertEquals("1:00:00", SonosTransportSettings.formatTime(3600))
        assertEquals("0:00:00", SonosTransportSettings.formatTime(-4))
    }

    @Test
    fun `shuffle and repeat apply to the queue only`() = runTest {
        val kitchen = room("Kitchen")
        val settings = client.transportSettings(kitchen.endpoint)
        val player = client.player(kitchen.endpoint, kitchen.uuid)
        val favs = client.favorites(kitchen.endpoint).list()

        player.play(favs.first { it.title == "Morning Radio" })
        assertFailsWith<SoapFault> { settings.setPlayMode(PlayMode(true, RepeatMode.OFF)) }

        player.play(favs.first { it.title == "Focus Playlist" })
        settings.setPlayMode(PlayMode(true, RepeatMode.ONE))
        assertEquals("SHUFFLE_REPEAT_ONE", fake.playMode(kitchen.uuid))
        assertEquals(PlayMode(true, RepeatMode.ONE), settings.playMode())
    }

    @Test
    fun `crossfade toggles`() = runTest {
        val s = client.transportSettings(room("Bedroom").endpoint)
        assertFalse(s.crossfade())
        s.setCrossfade(true)
        assertTrue(s.crossfade())
    }

    @Test
    fun `position and seek within a queued track, streams can't seek`() = runTest {
        val kitchen = room("Kitchen")
        val settings = client.transportSettings(kitchen.endpoint)
        val player = client.player(kitchen.endpoint, kitchen.uuid)
        val favs = client.favorites(kitchen.endpoint).list()

        player.play(favs.first { it.title == "Rainy Day Jazz" })
        client.queue(kitchen.endpoint, kitchen.uuid).playFrom(2)
        settings.seek(90)
        val p = settings.position()
        assertEquals(TrackPosition(track = 2, elapsedSeconds = 90, durationSeconds = 215), p)
        assertTrue(p.seekable)
        assertFailsWith<SoapFault> { settings.seek(10_000) }

        player.play(favs.first { it.title == "Morning Radio" })
        val radio = settings.position()
        assertFalse(radio.seekable)
        assertNull(radio.elapsedSeconds)
    }

    @Test
    fun `transport settings go to the coordinator`() = runTest {
        val loft = household.load(household.discover()).speakers.first { it.roomName == "Loft" && it.invisible }
        assertFailsWith<SoapFault> { client.transportSettings(loft.endpoint).playMode() }
    }
}

class SonosPlaylistsTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun room(name: String) = household.load(household.discover()).speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `sonos playlists list and play through the queue`() = runTest {
        val kitchen = room("Kitchen")
        val lists = client.playlists(kitchen.endpoint).list()
        assertEquals(listOf("Sunday Cleaning", "Road Trip"), lists.map { it.title })
        val road = lists[1]
        assertEquals("file:///jffs/settings/savedqueues.rsq#7", road.uri)
        assertTrue(road.needsQueue)
        assertTrue(road.canEnqueue)
        assertTrue("<container id=\"SQ:7\"" in road.metadata)

        client.player(kitchen.endpoint, kitchen.uuid).play(road)
        assertEquals(listOf("Road Trip, track 1", "Road Trip, track 2", "Road Trip, track 3"), client.queue(kitchen.endpoint, kitchen.uuid).list().map { it.title })
    }

    @Test
    fun `playlist DIDL skips containers without a res`() {
        val parsed = SonosPlaylists.parse(
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                "<container id=\"SQ:1\"><dc:title>A &amp; B</dc:title><res>file:///jffs/settings/savedqueues.rsq#1</res></container>" +
                "<container id=\"SQ:2\"><dc:title>Broken</dc:title></container></DIDL-Lite>",
        )
        assertEquals(listOf("A & B"), parsed.map { it.title })
        assertTrue("<dc:title>A &amp; B</dc:title>" in parsed[0].metadata)
    }

    @Test
    fun `play next inserts after the current track, add to queue appends`() = runTest {
        val kitchen = room("Kitchen")
        val player = client.player(kitchen.endpoint, kitchen.uuid)
        val queue = client.queue(kitchen.endpoint, kitchen.uuid)
        val favs = client.favorites(kitchen.endpoint).list()
        val jazz = favs.first { it.title == "Rainy Day Jazz" }

        player.play(favs.first { it.title == "Focus Playlist" })
        queue.playFrom(1)
        assertEquals(2, player.enqueue(jazz, next = true))
        assertEquals(
            listOf("Focus Playlist, track 1", "Rainy Day Jazz, track 1", "Rainy Day Jazz, track 2", "Rainy Day Jazz, track 3", "Focus Playlist, track 2"),
            queue.list().take(5).map { it.title },
        )

        val sunday = client.playlists(kitchen.endpoint).list().first()
        assertEquals(7, player.enqueue(sunday, next = false))
        assertEquals("Sunday Cleaning, track 3", queue.list().last().title)

        val radio = favs.first { it.title == "Morning Radio" }
        assertFalse(radio.canEnqueue)
        assertFailsWith<IllegalArgumentException> { player.enqueue(radio, next = true) }
    }
}

class SonosGenericPlayModeTest {
    @Test
    fun `sonos playback opts out of standard UPnP play modes`() = runTest {
        val fake = FakeSonosHousehold()
        val client = SonosClient(fake)
        val household = SonosHousehold(client, fake)
        val kitchen = household.load(household.discover()).speakers.first { it.roomName == "Kitchen" && !it.invisible }
        val p = client.playback(kitchen.endpoint, kitchen.endpoint)
        assertNull(p.playMode())
        assertFalse(p.supports(com.timborja.tugma.core.ShuffleRepeat(false, RepeatMode.ALL)))
    }
}
