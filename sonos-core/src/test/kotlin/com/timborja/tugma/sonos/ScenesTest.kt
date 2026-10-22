package com.timborja.tugma.sonos

import com.timborja.tugma.core.TransportState
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenesTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)
    private fun HouseholdState.room(name: String) = speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `a scene groups rooms, sets volumes and starts a favorite`() = runTest {
        var h = household.load(household.discover())
        // Start messy: Kitchen leads Office, which the scene doesn't want.
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Office")), h).confirm())
        h = household.refresh(h)

        val fav = client.favorites(h.room("Kitchen").endpoint).list().first { it.title == "Morning Radio" }
        val scene = SonosScene(
            id = "s1", name = "Morning",
            leaderUuid = h.room("Kitchen").uuid,
            memberUuids = listOf(h.room("Bedroom").uuid, h.room("Patio").uuid),
            volumes = mapOf(h.room("Kitchen").uuid to 18, h.room("Bedroom").uuid to 12),
            favorite = SceneFavorite.of(fav),
        )
        val plan = ScenePlanner.plan(scene, h)!!
        assertEquals("Scene: Morning", plan.title)
        client.execute(plan.confirm())
        h = household.refresh(h)

        val g = h.roomGroups().first { it.coordinator.roomName == "Kitchen" }
        assertEquals(setOf("Bedroom", "Patio"), g.members.map { it.roomName }.toSet())
        assertEquals(h.room("Office").uuid, h.coordinatorOf(h.room("Office").uuid)?.uuid)
        assertEquals(18, fake.renderers.getValue(h.room("Kitchen").uuid).volume)
        assertEquals(12, fake.renderers.getValue(h.room("Bedroom").uuid).volume)
        val status = client.playback(h.room("Kitchen").endpoint, h.room("Kitchen").endpoint).status()
        assertEquals(TransportState.PLAYING, status.transport)
        assertEquals("Morning Radio", status.title)
    }

    @Test
    fun `a leader that follows another room leaves first`() = runTest {
        var h = household.load(household.discover())
        client.execute(SonosGroupPlanner.join(h.room("Office"), listOf(h.room("Kitchen")), h).confirm())
        h = household.refresh(h)
        val scene = SonosScene("s2", "Kitchen only", h.room("Kitchen").uuid, listOf(h.room("Study").uuid))
        client.execute(ScenePlanner.plan(scene, h)!!.confirm())
        h = household.refresh(h)
        val g = h.roomGroups().first { it.coordinator.roomName == "Kitchen" }
        assertEquals(listOf("Study"), g.members.map { it.roomName })
    }

    @Test
    fun `playlists go through the queue, and missing rooms are reported`() = runTest {
        val h = household.load(household.discover())
        val fav = client.favorites(h.room("Kitchen").endpoint).list().first { it.needsQueue }
        val plan = ScenePlanner.plan(SonosScene("s3", "Focus", h.room("Office").uuid, favorite = SceneFavorite.of(fav)), h)!!
        assertTrue(plan.requests.any { "AddURIToQueue" in it.headers.getValue("SOAPACTION") })
        assertTrue(plan.warnings.any { "replaces" in it })

        val gone = SonosScene("s4", "Ghost", "RINCON_NOPE", listOf(h.room("Kitchen").uuid))
        assertNull(ScenePlanner.plan(gone, h))
        assertNotNull(ScenePlanner.problem(gone, h))
        assertNull(ScenePlanner.problem(SonosScene("s5", "ok", h.room("Kitchen").uuid), h))
    }

    @Test
    fun `scenes round-trip through JSON`() {
        val s = SonosScene("id", "Party", "A", listOf("B"), mapOf("A" to 30), SceneFavorite("Mix", "x-rincon-cpcontainer:1", "<DIDL/>", true))
        val json = Json.encodeToString(SonosScene.serializer(), s)
        assertEquals(s, Json.decodeFromString(SonosScene.serializer(), json))
    }
}

class SceneReminderTest {
    private val zone = java.time.ZoneId.of("Asia/Manila")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)

    @Test
    fun `next picks today if still ahead, else the next selected day`() {
        // 2026-10-22 is a Thursday (Sonos day 4).
        val weekdays7am = SceneReminder(7 * 60, (1..5).toSet())
        assertEquals(at(2026, 10, 22, 7, 0), weekdays7am.next(at(2026, 10, 22, 6, 30)))
        assertEquals(at(2026, 10, 23, 7, 0), weekdays7am.next(at(2026, 10, 22, 7, 0)))
        // Friday evening → Monday morning.
        assertEquals(at(2026, 10, 26, 7, 0), weekdays7am.next(at(2026, 10, 23, 20, 0)))
        assertEquals("07:00 · Weekdays", weekdays7am.label)
    }

    @Test
    fun `no days or a bad time means no reminder`() {
        assertNull(SceneReminder(420, emptySet()).next(at(2026, 10, 22, 6, 0)))
        assertNull(SceneReminder(24 * 60, setOf(4)).next(at(2026, 10, 22, 6, 0)))
    }

    @Test
    fun `scenes without a reminder still decode`() {
        val old = """{"id":"a","name":"x","leaderUuid":"L"}"""
        assertNull(Json.decodeFromString(SonosScene.serializer(), old).reminder)
    }
}
