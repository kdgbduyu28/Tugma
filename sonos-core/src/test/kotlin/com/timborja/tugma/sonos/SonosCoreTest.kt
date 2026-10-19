package com.timborja.tugma.sonos

import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.DeviceDescription
import com.timborja.tugma.upnp.SoapFault
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resource(name: String): String =
    requireNotNull(SonosCoreTest::class.java.getResource("/$name")) { "missing $name" }.readText()

class SonosCoreTest {
    @Test
    fun `parses SoCo basic payload`() {
        val t = Topology.parse(resource("zgs_soco_basic.xml"))
        assertEquals(3, t.groups.size)
        assertEquals(4, t.allMembers.size)
        val kitchen = t.member("RINCON_000XXX1400")!!
        assertEquals("Kitchen", kitchen.zoneName)
        assertEquals("192.168.1.101", kitchen.host)
        assertEquals(1400, kitchen.port)
        assertEquals("RINCON_000XXX1400", t.groupOf("RINCON_B8E93781F3EA01400")!!.coordinator)
        assertTrue(t.allMembers.all { t.roleOf(it.uuid) == BondRole.Standalone })
    }

    @Test
    fun `multi-port amp keeps distinct ports on one ip`() {
        val t = Topology.parse(resource("zgs_soco_amp_multi.xml"))
        assertEquals(listOf(1400, 1500, 1600, 1700), t.allMembers.map { it.port })
        assertEquals(setOf("192.0.2.10"), t.allMembers.map { it.host }.toSet())
    }

    @Test
    fun `roles from stereo, home theater, sub and bridge`() {
        val t = Topology.parse(resource("zgs_stereo_and_ht.xml"))
        assertEquals(10, t.allMembers.size)

        val bar = assertIs<BondRole.HtPrimary>(t.roleOf("RINCON_BAR000000001400"))
        assertEquals(listOf("RINCON_SUB000000001400", "RINCON_SLL00000001400", "RINCON_SLR00000001400"), bar.satellites.map { it.uuid })
        assertEquals(BondRole.HtSatellite("RINCON_BAR000000001400", "SW"), t.roleOf("RINCON_SUB000000001400"))
        assertEquals(BondRole.HtSatellite("RINCON_BAR000000001400", "LR"), t.roleOf("RINCON_SLL00000001400"))
        assertEquals(BondRole.HtSatellite("RINCON_BAR000000001400", "RR"), t.roleOf("RINCON_SLR00000001400"))

        assertEquals(BondRole.StereoLeft("RINCON_RIGHT000001400"), t.roleOf("RINCON_LEFT0000001400"))
        assertEquals(BondRole.StereoRight("RINCON_LEFT0000001400"), t.roleOf("RINCON_RIGHT000001400"))
        assertEquals(BondRole.Standalone, t.roleOf("RINCON_KITCHEN00001400"))

        assertEquals(BondRole.WithSub("RINCON_DENSUB0000001400"), t.roleOf("RINCON_FIVE00000001400"))
        assertEquals(BondRole.BondedSub("RINCON_FIVE00000001400"), t.roleOf("RINCON_DENSUB0000001400"))
        assertEquals(BondRole.Bridge, t.roleOf("RINCON_BRIDGE000001400"))

        assertTrue(t.member("RINCON_RIGHT000001400")!!.invisible)
        assertTrue(t.member("RINCON_SUB000000001400")!!.isSatellite)
    }

    @Test
    fun `channel map parse and format round trip`() {
        val s = "RINCON_BAR:LF,RF;RINCON_A:LR;RINCON_B:RR;RINCON_C:SW"
        assertEquals(s, ChannelMap.format(ChannelMap.parse(s)))
        assertEquals("RINCON_L:LF,LF;RINCON_R:RF,RF", ChannelMap.stereoPair("RINCON_L", "RINCON_R"))
        assertEquals(emptyList(), ChannelMap.parse(""))
    }

    @Test
    fun `device description to info`() {
        val info = SonosDeviceInfo.from(DeviceDescription.parse(resource("device_description_arc.xml")))
        assertEquals("RINCON_BAR000000001400", info.uuid)
        assertEquals("Living Room", info.roomName)
        assertEquals("Sonos Arc", info.modelName)
        assertEquals("S19", info.modelNumber)
        assertTrue(info.isSoundbar)
        assertFalse(info.isSub)
        val desc = DeviceDescription.parse(resource("device_description_arc.xml"))
        assertTrue(desc.services.any { it.controlUrl == "/MediaRenderer/RenderingControl/Control" })
    }

    @Test
    fun `soundbar and sub detection`() {
        fun info(model: String, display: String? = null) = SonosDeviceInfo("u", null, model, null, display, null, null)
        listOf("Sonos Arc", "Sonos Arc Ultra", "Sonos Beam", "Sonos Ray", "Sonos Amp", "Sonos Playbar").forEach { assertTrue(info(it).isSoundbar, it) }
        listOf("Sonos One", "Sonos Era 300", "Sonos Five", "Sonos Sub").forEach { assertFalse(info(it).isSoundbar, it) }
        listOf("Sonos Sub", "Sonos Sub Mini").forEach { assertTrue(info(it).isSub, it) }
        assertFalse(info("Sonos Subway").isSub)
    }
}

class PlannerAndRulesTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun state() = household.load(household.discover())
    private fun HouseholdState.byRoom(room: String) = speakers.first { it.roomName == room && !it.invisible }

    @Test
    fun `stereo plan targets the left speaker with AddBondedZones`() = runTest {
        val s = state()
        val left = s.byRoom("Kitchen")
        val right = s.byRoom("Bedroom")
        val plan = SonosPlanner.stereoPair(left, right, legacy = false)
        val req = plan.requests.single()
        assertEquals("http://192.168.50.12:1400/DeviceProperties/Control", req.url)
        assertEquals("\"urn:schemas-upnp-org:service:DeviceProperties:1#AddBondedZones\"", req.headers["SOAPACTION"])
        assertTrue("<ChannelMapSet>${left.uuid}:LF,LF;${right.uuid}:RF,RF</ChannelMapSet>" in req.body)
    }

    @Test
    fun `legacy pairing only when AddBondedZones is missing`() {
        assertFalse(SonosPlanner.useLegacyPairing(setOf("AddBondedZones", "CreateStereoPair")))
        assertTrue(SonosPlanner.useLegacyPairing(setOf("CreateStereoPair", "SeparateStereoPair")))
        assertFalse(SonosPlanner.useLegacyPairing(null))
    }

    @Test
    fun `unpair sends empty map and KeepGrouped 0`() = runTest {
        val s = state()
        val loft = s.byRoom("Loft")
        val body = SonosPlanner.separateStereoPair(loft, null, legacy = false).requests.single().body
        assertTrue("<u:RemoveBondedZones" in body)
        assertTrue("<ChannelMapSet></ChannelMapSet><KeepGrouped>0</KeepGrouped>" in body)
    }

    @Test
    fun `stereo rules allow mixed models but block bonded and subs`() = runTest {
        val s = state()
        val mixed = StereoPairRules.check(s.byRoom("Kitchen"), s.byRoom("Bedroom"), s)
        assertFalse(mixed.blocked)
        assertTrue(mixed.any { it.severity == Severity.INFO && "Different models" in it.message })

        assertTrue(StereoPairRules.check(s.byRoom("Kitchen"), s.byRoom("Loft"), s).blocked)
        assertTrue(StereoPairRules.check(s.byRoom("Kitchen"), s.byRoom("Den"), s).blocked)
        assertTrue(StereoPairRules.check(s.byRoom("Kitchen"), s.byRoom("Kitchen"), s).blocked)
        val bar = StereoPairRules.check(s.byRoom("Living Room"), s.byRoom("Kitchen"), s)
        assertFalse(bar.blocked)
        assertTrue(bar.any { it.severity == Severity.WARN && "soundbar" in it.message })
    }

    @Test
    fun `home theater map and rules`() = runTest {
        val s = state()
        val bar = s.byRoom("Living Room")
        val slots = mapOf(HtSlot.LEFT_SURROUND to s.byRoom("Kitchen"), HtSlot.RIGHT_SURROUND to s.byRoom("Bedroom"), HtSlot.SUB to s.byRoom("Den"))
        assertEquals(
            "${bar.uuid}:LF,RF;${s.byRoom("Kitchen").uuid}:LR;${s.byRoom("Bedroom").uuid}:RR;${s.byRoom("Den").uuid}:SW",
            HomeTheaterRules.channelMap(bar, slots),
        )
        assertFalse(HomeTheaterRules.check(bar, slots, s, allowExperimental = false).blocked)

        assertTrue(HomeTheaterRules.check(s.byRoom("Kitchen"), mapOf(HtSlot.SUB to s.byRoom("Den")), s, false).blocked, "not a soundbar")
        assertTrue(HomeTheaterRules.check(bar, mapOf(HtSlot.FRONT_LEFT to s.byRoom("Kitchen")), s, false).blocked, "experimental off")
        assertFalse(HomeTheaterRules.check(bar, mapOf(HtSlot.FRONT_LEFT to s.byRoom("Kitchen")), s, true).blocked, "experimental on")
        assertTrue(HomeTheaterRules.check(bar, mapOf(HtSlot.LEFT_SURROUND to s.byRoom("Den")), s, false).blocked, "sub as surround")
        assertTrue(HomeTheaterRules.check(bar, mapOf(HtSlot.LEFT_SURROUND to s.byRoom("Loft")), s, false).blocked, "already paired")
        assertTrue(HomeTheaterRules.check(bar, emptyMap(), s, false).blocked, "nothing to do")
        val one = HomeTheaterRules.check(bar, mapOf(HtSlot.LEFT_SURROUND to s.byRoom("Kitchen")), s, false)
        assertTrue(one.any { "Only one surround" in it.message })
        val nonSub = HomeTheaterRules.check(bar, mapOf(HtSlot.SUB to s.byRoom("Office")), s, false)
        assertTrue(nonSub.any { it.severity == Severity.WARN && "isn't a Sub" in it.message })
    }
}

class FakeHouseholdRoundTripTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    private suspend fun state() = household.load(household.discover())
    private fun HouseholdState.byRoom(room: String) = speakers.first { it.roomName == room && !it.invisible }

    @Test
    fun `discovery hides bonded secondaries but load sees everyone`() = runTest {
        assertEquals(8, household.discover().size)
        val s = state()
        assertEquals(9, s.speakers.size)
        assertEquals("Sonos Arc", s.byRoom("Living Room").modelName)
        assertIs<BondRole.StereoLeft>(s.byRoom("Loft").role)
        assertTrue(s.speakers.all { it.describeError == null })
    }

    @Test
    fun `pair then separate`() = runTest {
        var s = state()
        val plan = SonosPlanner.stereoPair(s.byRoom("Kitchen"), s.byRoom("Bedroom"), legacy = false)
        client.execute(plan.confirm())
        s = household.refresh(s)
        val kitchen = s.byRoom("Kitchen")
        val role = assertIs<BondRole.StereoLeft>(kitchen.role)
        assertEquals(s.speakers.first { it.modelName == "Sonos Era 100" }.uuid, role.partner)
        assertNull(s.speakers.firstOrNull { it.roomName == "Bedroom" }, "right speaker is now part of Kitchen")

        client.execute(SonosPlanner.separateStereoPair(kitchen, null, legacy = false).confirm())
        s = household.refresh(s)
        assertEquals(BondRole.Standalone, s.byRoom("Kitchen").role)
        assertEquals(BondRole.Standalone, s.byRoom("Bedroom").role)
        assertEquals(listOf("GetZoneGroupState", "AddBondedZones", "GetZoneGroupState", "RemoveBondedZones", "GetZoneGroupState"), fake.receivedActions)
    }

    @Test
    fun `home theater add then remove one satellite`() = runTest {
        var s = state()
        val bar = s.byRoom("Living Room")
        val slots = mapOf(HtSlot.LEFT_SURROUND to s.byRoom("Kitchen"), HtSlot.RIGHT_SURROUND to s.byRoom("Study"), HtSlot.SUB to s.byRoom("Den"))
        val plan = SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, slots), "", emptyList())
        client.execute(plan.confirm())
        s = household.refresh(s)
        val primary = assertIs<BondRole.HtPrimary>(s.byRoom("Living Room").role)
        assertEquals(setOf("LR", "RR", "SW"), primary.satellites.map { it.channelSet.single() }.toSet())
        val existing = HomeTheaterRules.existing(s.byRoom("Living Room"), s)
        assertEquals(setOf(HtSlot.LEFT_SURROUND, HtSlot.RIGHT_SURROUND, HtSlot.SUB), existing.keys)

        val sub = existing.getValue(HtSlot.SUB)
        client.execute(SonosPlanner.removeHtSatellite(s.byRoom("Living Room"), sub.uuid, sub.roomName).confirm())
        s = household.refresh(s)
        assertEquals(setOf(HtSlot.LEFT_SURROUND, HtSlot.RIGHT_SURROUND), HomeTheaterRules.existing(s.byRoom("Living Room"), s).keys)
        assertEquals(BondRole.Standalone, s.byRoom("Den").role)
    }

    @Test
    fun `speaker faults surface as PlanExecutionException`() = runTest {
        val s = state()
        // Kitchen isn't a soundbar, so the fake rejects AddHTSatellite like a real speaker would.
        val bogus = SonosPlanner.addHtSatellites(s.byRoom("Kitchen"), "${s.byRoom("Kitchen").uuid}:LF,RF;${s.byRoom("Den").uuid}:SW", "", emptyList())
        val e = assertFailsWith<PlanExecutionException> { client.execute(bogus.confirm()) }
        assertEquals(800, assertIs<SoapFault>(e.cause).upnpErrorCode)
    }

    @Test
    fun `seeding from a satellite re-reads topology from its soundbar`() = runTest {
        var s = state()
        val bar = s.byRoom("Living Room")
        client.execute(SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, mapOf(HtSlot.SUB to s.byRoom("Den"))), "", emptyList()).confirm())
        s = household.load(listOf(Endpoint("192.168.50.15")))
        assertEquals(Endpoint("192.168.50.11"), s.source)
    }
}

class RestorePlannerTest {
    private val fake = FakeSonosHousehold()
    private val client = SonosClient(fake)
    private val household = SonosHousehold(client, fake)

    @Test
    fun `restore undoes a new pair and re-creates a separated one`() = runTest {
        var s = household.load(household.discover())
        val snap = TopologySnapshot.of("1", "test", s, 0)
        assertNull(RestorePlanner.plan(snap, s))

        val kitchen = s.speakers.first { it.roomName == "Kitchen" }
        val bedroom = s.speakers.first { it.roomName == "Bedroom" }
        val loft = s.speakers.first { it.roomName == "Loft" && !it.invisible }
        client.execute(SonosPlanner.stereoPair(kitchen, bedroom, legacy = false).confirm())
        client.execute(SonosPlanner.separateStereoPair(loft, null, legacy = false).confirm())
        s = household.refresh(s)
        assertTrue(RestorePlanner.diff(snap, s).isNotEmpty())

        val restore = assertNotNull(RestorePlanner.plan(snap, s))
        assertEquals(listOf("RemoveBondedZones", "AddBondedZones"), restore.requests.map { it.label.substringBefore(" ") })
        client.execute(restore.confirm())
        s = household.refresh(s)
        assertTrue(RestorePlanner.diff(snap, s).isEmpty(), RestorePlanner.diff(snap, s).toString())
    }

    @Test
    fun `restore re-adds removed satellites with the snapshot map`() = runTest {
        var s = household.load(household.discover())
        val bar = s.speakers.first { it.roomName == "Living Room" }
        val slots = mapOf(HtSlot.LEFT_SURROUND to s.speakers.first { it.roomName == "Kitchen" }, HtSlot.RIGHT_SURROUND to s.speakers.first { it.roomName == "Study" })
        client.execute(SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, slots), "", emptyList()).confirm())
        s = household.refresh(s)
        val snap = TopologySnapshot.of("2", "before remove", s, 0)

        val kitchenUuid = slots.getValue(HtSlot.LEFT_SURROUND).uuid
        client.execute(SonosPlanner.removeHtSatellite(s.speaker(bar.uuid)!!, kitchenUuid, "Kitchen").confirm())
        s = household.refresh(s)
        val restore = assertNotNull(RestorePlanner.plan(snap, s))
        assertEquals("AddHTSatellite", restore.requests.single().label.substringBefore(" "))
        client.execute(restore.confirm())
        s = household.refresh(s)
        assertTrue(RestorePlanner.diff(snap, s).isEmpty())
    }
}
