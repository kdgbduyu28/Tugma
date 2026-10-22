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

class SonosGroupsTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun load() = household.load(household.discover())
    private fun HouseholdState.room(name: String) = speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `every playable room starts in its own group, bonded halves hidden`() = runTest {
        val h = load()
        val groups = h.roomGroups()
        assertTrue(groups.all { it.members.isEmpty() })
        assertEquals(1, groups.count { it.coordinator.roomName == "Loft" })
        assertFalse(groups.any { it.coordinator.invisible })
    }

    @Test
    fun `join sends SetAVTransportURI with x-rincon to each new member`() = runTest {
        val h = load()
        val plan = SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom"), h.room("Study")), h)
        assertEquals(2, plan.requests.size)
        assertTrue(plan.requests.all { "SetAVTransportURI" in it.headers.getValue("SOAPACTION") })
        assertTrue(plan.requests[0].url.startsWith("http://192.168.50.13:1400/MediaRenderer/AVTransport/Control"))
        assertTrue("<CurrentURI>x-rincon:${h.room("Kitchen").uuid}</CurrentURI>" in plan.requests[0].body)

        client.execute(plan.confirm())
        val after = household.refresh(h)
        val kitchen = after.roomGroups().first { it.coordinator.roomName == "Kitchen" }
        assertEquals(setOf("Bedroom", "Study"), kitchen.members.map { it.roomName }.toSet())
        assertEquals("Kitchen + 2", kitchen.name)
        assertEquals(after.room("Kitchen").uuid, after.coordinatorOf(after.room("Study").uuid)?.uuid)
    }

    @Test
    fun `members already in the group aren't sent again, and leaving makes rooms standalone`() = runTest {
        var h = load()
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom")), h).confirm())
        h = household.refresh(h)
        val again = SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom"), h.room("Office")), h)
        assertEquals(1, again.requests.size)
        assertTrue(SonosGroupRules.check(h.room("Kitchen"), listOf(h.room("Bedroom")), h).any { it.severity == Severity.INFO })

        client.execute(SonosGroupPlanner.ungroup(h.roomGroups().first { it.coordinator.roomName == "Kitchen" }).confirm())
        h = household.refresh(h)
        assertTrue(h.roomGroups().all { it.members.isEmpty() })
    }

    @Test
    fun `a leaving coordinator hands the group to the next member`() = runTest {
        var h = load()
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom"), h.room("Study")), h).confirm())
        h = household.refresh(h)
        client.execute(SonosGroupPlanner.leave(listOf(h.room("Kitchen"))).confirm())
        h = household.refresh(h)
        val rest = h.roomGroups().first { it.members.isNotEmpty() }
        assertEquals(setOf("Bedroom", "Study"), rest.all.map { it.roomName }.toSet())
    }

    @Test
    fun `party mode groups every room, and stereo pairs join as one room`() = runTest {
        var h = load()
        client.execute(SonosGroupPlanner.everything(h.room("Office"), h).confirm())
        h = household.refresh(h)
        val groups = h.roomGroups()
        assertEquals(1, groups.size)
        assertEquals("Office", groups.single().coordinator.roomName)
        assertTrue(groups.single().members.any { it.roomName == "Loft" })
        // The hidden right half of the Loft pair follows its left speaker; it never gets its own request.
        assertFalse(SonosGroupPlanner.everything(h.room("Office"), load()).requests.any { "192.168.50.19" in it.url })
    }

    @Test
    fun `rules block bonded halves, subs and self-joins`() = runTest {
        val h = load()
        val right = h.speakers.first { it.invisible }
        assertTrue(SonosGroupRules.check(h.room("Kitchen"), listOf(right), h).blocked)
        assertTrue(SonosGroupRules.check(h.room("Kitchen"), listOf(h.room("Kitchen")), h).blocked)
        assertTrue(SonosGroupRules.check(h.room("Kitchen"), emptyList(), h).blocked)
        assertTrue(SonosGroupRules.check(null, listOf(h.room("Kitchen")), h).blocked)
        assertFalse(SonosGroupRules.canGroup(h.room("Den"))) // standalone Sub Mini
        assertFalse(SonosGroupRules.check(h.room("Kitchen"), listOf(h.room("Patio")), h).blocked)
    }

    @Test
    fun `joining a non-coordinator is refused like a real speaker`() = runTest {
        var h = load()
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom")), h).confirm())
        h = household.refresh(h)
        // Hand-built plan pointing Study at Bedroom, which is only a member.
        val bad = SonosGroupPlanner.join(h.room("Bedroom"), listOf(h.room("Study")), h)
        assertFailsWith<PlanExecutionException> { client.execute(bad.confirm()) }
    }

    @Test
    fun `group volume and mute reach every member, and members refuse transport`() = runTest {
        var h = load()
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom")), h).confirm())
        h = household.refresh(h)
        val gv = client.groupVolume(h.room("Kitchen").endpoint)
        gv.setVolume(35)
        assertEquals(35, gv.volume())
        assertEquals(35, fake.renderers.getValue(h.room("Bedroom").uuid).volume)
        gv.setMuted(true)
        assertEquals(true, gv.muted())
        assertTrue("SnapshotGroupVolume" in fake.receivedActions)
        assertFailsWith<SoapFault> { client.groupVolume(h.room("Bedroom").endpoint).volume() }

        val memberTransport = client.playback(h.room("Bedroom").endpoint, h.room("Bedroom").endpoint)
        assertFailsWith<SoapFault> { memberTransport.play() }
        val viaCoordinator = client.playback(h.room("Bedroom").endpoint, h.coordinatorOf(h.room("Bedroom").uuid)!!.endpoint)
        viaCoordinator.play()
        assertEquals(TransportState.PLAYING, viaCoordinator.status().transport)
    }
}

class SonosSoundTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    @Test
    fun `plain speakers report bass, treble and loudness only`() = runTest {
        val h = household.load(household.discover())
        val kitchen = h.speakers.first { it.roomName == "Kitchen" }
        val state = client.sound(kitchen.endpoint).read(homeTheater = true)
        assertEquals(listOf(SoundSetting.BASS, SoundSetting.TREBLE, SoundSetting.LOUDNESS), state.supported)
        assertEquals(true, state.on(SoundSetting.LOUDNESS))
    }

    @Test
    fun `soundbar settings appear as satellites are added`() = runTest {
        var h = household.load(household.discover())
        val bar = h.speakers.first { it.roomName == "Living Room" }
        val sound = client.sound(bar.endpoint)
        val before = sound.read(homeTheater = true)
        assertTrue(SoundSetting.NIGHT_MODE in before.supported)
        assertTrue(SoundSetting.HEIGHT_LEVEL in before.supported)
        assertNull(before[SoundSetting.SUB_GAIN])

        val sub = h.speakers.first { it.roomName == "Den" }
        val rl = h.speakers.first { it.roomName == "Study" }
        val rr = h.speakers.first { it.roomName == "Bedroom" }
        val slots = mapOf(HtSlot.SUB to sub, HtSlot.LEFT_SURROUND to rl, HtSlot.RIGHT_SURROUND to rr)
        client.execute(SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, slots), "", emptyList()).confirm())
        h = household.refresh(h)

        val after = sound.read(homeTheater = true)
        assertEquals(0, after[SoundSetting.SUB_GAIN])
        assertTrue(SoundSetting.SURROUND_FULL_VOLUME in after.supported)
        sound.set(SoundSetting.AUDIO_DELAY, 9)
        assertEquals(5, sound.get(SoundSetting.AUDIO_DELAY)) // clamped to the 0-5 range
        assertEquals(true, after.on(SoundSetting.SURROUND_ENABLED))
        assertTrue(SoundSetting.MUSIC_SURROUND_LEVEL in after.supported)
    }

    @Test
    fun `setters clamp to range, send the documented arguments and reset restores defaults`() = runTest {
        val h = household.load(household.discover())
        val bar = h.speakers.first { it.roomName == "Living Room" }
        val sound = client.sound(bar.endpoint)
        sound.set(SoundSetting.BASS, 25)
        sound.set(SoundSetting.TREBLE, -3)
        sound.set(SoundSetting.LOUDNESS, 0)
        sound.set(SoundSetting.NIGHT_MODE, 1)
        sound.set(SoundSetting.HEIGHT_LEVEL, -4)
        assertEquals(10, fake.soundValue(bar.uuid, "Bass"))
        assertEquals(-3, fake.soundValue(bar.uuid, "Treble"))
        assertEquals(0, fake.soundValue(bar.uuid, "Loudness"))
        assertEquals(1, fake.soundValue(bar.uuid, "NightMode"))

        val state = sound.read(homeTheater = true)
        sound.reset(state)
        val reset = sound.read(homeTheater = true)
        assertEquals(0, reset[SoundSetting.BASS])
        assertEquals(0, reset[SoundSetting.TREBLE])
        assertEquals(0, reset[SoundSetting.HEIGHT_LEVEL])
        assertEquals(true, reset.on(SoundSetting.LOUDNESS))
        assertEquals(true, reset.on(SoundSetting.NIGHT_MODE)) // switches other than loudness are left alone
    }

    @Test
    fun `unsupported EQ is a fault on set`() = runTest {
        val h = household.load(household.discover())
        val kitchen = h.speakers.first { it.roomName == "Kitchen" }
        assertFailsWith<SoapFault> { client.sound(kitchen.endpoint).set(SoundSetting.NIGHT_MODE, 1) }
    }
}

class GroupRestoreTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)
    private fun HouseholdState.room(name: String) = speakers.first { it.roomName == name && !it.invisible }

    @Test
    fun `restoring a snapshot brings groups back and undoes new ones`() = runTest {
        var h = household.load(household.discover())
        client.execute(SonosGroupPlanner.join(h.room("Kitchen"), listOf(h.room("Bedroom"), h.room("Study")), h).confirm())
        h = household.refresh(h)
        val snapshot = TopologySnapshot.of("s1", "before party", h, 0)
        assertTrue(RestorePlanner.diff(snapshot, h).isEmpty())
        assertNull(RestorePlanner.plan(snapshot, h))

        // Afterwards: Kitchen's group is broken up, Bedroom now leads Office.
        client.execute(SonosGroupPlanner.ungroup(h.roomGroups().first { it.coordinator.roomName == "Kitchen" }).confirm())
        h = household.refresh(h)
        client.execute(SonosGroupPlanner.join(h.room("Bedroom"), listOf(h.room("Office")), h).confirm())
        h = household.refresh(h)

        val diff = RestorePlanner.diff(snapshot, h)
        assertTrue(diff.any { it.room == "Office" && it.before == "Not grouped" && it.after == "Grouped with Bedroom" })
        assertTrue(diff.any { it.room == "Study" && it.before == "Grouped with Kitchen" })

        val plan = RestorePlanner.plan(snapshot, h)!!
        client.execute(plan.confirm())
        h = household.refresh(h)
        val kitchen = h.roomGroups().first { it.coordinator.roomName == "Kitchen" }
        assertEquals(setOf("Bedroom", "Study"), kitchen.members.map { it.roomName }.toSet())
        assertEquals(h.room("Office").uuid, h.coordinatorOf(h.room("Office").uuid)?.uuid)
        assertTrue(RestorePlanner.diff(snapshot, h).isEmpty())
    }
}

class PauseAllTest {
    @Test
    fun `pauseAll pauses only the groups that are playing`() = runTest {
        val fake = FakeSonosHousehold()
        val client = SonosClient(fake)
        val h = SonosHousehold(client, fake).load(fake.search().let { r -> r.map { Endpoint(it.host, it.port) } })
        val kitchen = h.speakers.first { it.roomName == "Kitchen" }
        val office = h.speakers.first { it.roomName == "Office" }
        client.playback(kitchen.endpoint, kitchen.endpoint).play()
        client.playback(office.endpoint, office.endpoint).play()
        assertEquals(2, client.pauseAll(h))
        assertEquals(TransportState.PAUSED, client.playback(kitchen.endpoint, kitchen.endpoint).status().transport)
        assertEquals(0, client.pauseAll(h))

        client.playback(office.endpoint, office.endpoint).play()
        val paused = client.pauseAllGroups(h)
        assertEquals(listOf("Office"), paused.map { it.roomName })
        assertEquals(1, client.resume(paused))
        assertEquals(TransportState.PLAYING, client.playback(office.endpoint, office.endpoint).status().transport)
        assertEquals(TransportState.PAUSED, client.playback(kitchen.endpoint, kitchen.endpoint).status().transport)
    }
}
