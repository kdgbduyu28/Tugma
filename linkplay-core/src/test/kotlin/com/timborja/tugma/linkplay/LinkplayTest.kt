package com.timborja.tugma.linkplay

import com.timborja.tugma.core.TransportState
import com.timborja.tugma.linkplay.demo.FakeLinkplayHousehold
import com.timborja.tugma.upnp.HttpResponse
import com.timborja.tugma.upnp.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkplayTest {
    private val fake = FakeLinkplayHousehold()
    private val client = LinkplayClient(fake)
    private val household = LinkplayHousehold(client)

    private suspend fun load() = household.load(fake.speakers.map { it.host } + "192.168.50.99")
    private fun List<LinkplayDevice>.named(n: String) = first { it.name == n }

    @Test
    fun `url escaping matches the API's plus-sign rule`() {
        assertEquals("http://10.0.0.2/httpapi.asp?command=setPlayerCmd:vol%2b%2b", LinkplayClient.url("10.0.0.2", "setPlayerCmd:vol++", false))
        assertEquals("https://10.0.0.2/httpapi.asp?command=getStatusEx", LinkplayClient.url("10.0.0.2", "getStatusEx", true))
    }

    @Test
    fun `hex titles decode, junk passes through`() {
        assertEquals("Demo Track", LinkplayClient.hexText("44656D6F20547261636B"))
        assertEquals("Not hex!", LinkplayClient.hexText("Not hex!"))
        assertNull(LinkplayClient.hexText("756E6B6E6F776E")) // "unknown"
        assertNull(LinkplayClient.hexText(""))
    }

    @Test
    fun `probing finds Linkplay speakers over http and https and skips others`() = runTest {
        val all = load()
        assertEquals(listOf("Kusina Amp", "Sala Left", "Sala Right"), all.map { it.name })
        assertTrue(all.named("Sala Right").https, "WiiM-style speaker answered over HTTPS")
        assertEquals("A98", all.named("Sala Left").hardware)
    }

    @Test
    fun `lenient json survives the malformed example in the docs`() {
        val o = client.parse("""{"status":"play","vol":"40","mute":"0}""")
        // Lenient parsing either recovers or returns null; it must not throw.
        assertTrue(o == null || o.str("status") == "play")
    }

    @Test
    fun `stereo pair across models groups then splits channels`() = runTest {
        var all = load()
        val plan = LinkplayPlanner.stereoPair(all.named("Sala Left"), all.named("Sala Right"))
        assertEquals(3, plan.requests.size)
        assertTrue(plan.requests[0].url.startsWith("https://192.168.50.53/"), "join is sent to the guest")
        assertTrue("ConnectMasterAp:JoinGroupMaster:eth192.168.50.52:wifi0.0.0.0" in plan.requests[0].url)
        assertTrue(plan.warnings.single().contains("isn't in Linkplay's published API"))
        client.execute(plan.confirm())

        all = load()
        val host = all.named("Sala Left")
        assertEquals(LinkplayChannel.LEFT, host.channel)
        val guest = host.guests.single()
        assertEquals("Sala Right", guest.name)
        assertEquals(LinkplayChannel.RIGHT, guest.channel)
        assertTrue(all.named("Sala Right").isGuest)
        assertTrue(host.roleLabel!!.contains("Sala Right (right)"))

        client.execute(LinkplayPlanner.ungroup(host).confirm())
        all = load()
        assertTrue(all.none { it.isGuest })
        assertEquals(LinkplayChannel.STEREO, all.named("Sala Left").channel)
    }

    @Test
    fun `group, kick, and failures surface`() = runTest {
        var all = load()
        val amp = all.named("Kusina Amp")
        client.execute(LinkplayPlanner.group(amp, listOf(all.named("Sala Left"), all.named("Sala Right"))).confirm())
        all = load()
        assertEquals(2, all.named("Kusina Amp").guests.size)
        client.execute(LinkplayPlanner.kick(amp, "192.168.50.52", "Sala Left").confirm())
        assertEquals(listOf("Sala Right"), load().named("Kusina Amp").guests.map { it.name })

        val e = assertFailsWith<IOException> { client.execute(LinkplayPlanner.group(amp, listOf(amp)).confirm()) }
        assertTrue("Failed" in e.message!!)
    }

    @Test
    fun `playback through the player commands`() = runTest {
        val d = load().named("Kusina Amp")
        val p = client.playback(d)
        p.setVolume(150)
        p.setMuted(true)
        p.play()
        val s = p.status()
        assertEquals(100, s.volume)
        assertEquals(true, s.muted)
        assertEquals(TransportState.PLAYING, s.transport)
        assertEquals("Demo Track", s.title)
        assertEquals("Tugma FM", s.artist)
        p.pause()
        assertEquals(TransportState.PAUSED, p.status().transport)
    }

    @Test
    fun `speaker error bodies fail the plan`() = runTest {
        val unknown = LinkplayClient(HttpTransport { HttpResponse(200, "unknown command") })
        val d = LinkplayDevice("u", "X", "10.0.0.9", false, null, null, false, null)
        assertFailsWith<IOException> { unknown.execute(LinkplayPlanner.ungroup(d).confirm()) }
    }
}

class LinkplayExtrasTest {
    private val fake = FakeLinkplayHousehold()
    private val client = LinkplayClient(fake)

    private suspend fun device(name: String) =
        LinkplayHousehold(client).load(fake.speakers.map { it.host }).first { it.name == name }

    @Test
    fun `presets play by number and change what's playing`() = runTest {
        val amp = device("Kusina Amp")
        val extras = LinkplayExtras(client, amp)
        assertNull(extras.presets()) // plain Linkplay firmware here doesn't list them
        extras.playPreset(2)
        val status = client.playback(amp).status()
        assertEquals(TransportState.PLAYING, status.transport)
        assertEquals("Focus Playlist", status.title)
        assertFailsWith<IllegalArgumentException> { extras.playPreset(11) }
    }

    @Test
    fun `WiiM-style speakers list presets and EQ`() = runTest {
        val wiim = device("Sala Right")
        val extras = LinkplayExtras(client, wiim)
        assertEquals(listOf("Morning Radio", "Focus Playlist", "Rainy Day Jazz"), extras.presets()?.map { it.name })
        assertEquals(false, extras.eqEnabled())
        assertTrue("Bass Booster" in extras.eqPresets())
        extras.loadEq("Bass Booster")
        assertEquals(true, extras.eqEnabled())
        assertEquals("Bass Booster", fake.speakers.first { it.name == "Sala Right" }.eqPreset)
        extras.setEq(false)
        assertEquals(false, extras.eqEnabled())
        assertFailsWith<IllegalStateException> { extras.loadEq("Nope") }
    }

    @Test
    fun `EQ is reported missing on firmware without it`() = runTest {
        val extras = LinkplayExtras(client, device("Sala Left"))
        assertNull(extras.eqEnabled())
        assertTrue(extras.eqPresets().isEmpty())
    }
}

class LinkplayPlayUrlTest {
    @Test
    fun `playUrl sends setPlayerCmd play`() = runTest {
        val fake = FakeLinkplayHousehold()
        val client = LinkplayClient(fake)
        val amp = LinkplayHousehold(client).load(fake.speakers.map { it.host }).first { it.name == "Kusina Amp" }
        (client.playback(amp) as com.timborja.tugma.core.UrlPlayback).playUrl("http://radio.example/live.mp3", "Example FM")
        assertTrue(fake.commands.any { it.endsWith("setPlayerCmd:play:http://radio.example/live.mp3") })
        assertEquals(TransportState.PLAYING, client.playback(amp).status().transport)
    }
}

class LinkplayRenameTest {
    @Test
    fun `rename uses setDeviceName and shows on the next probe`() = runTest {
        val fake = FakeLinkplayHousehold()
        val client = LinkplayClient(fake)
        val amp = LinkplayHousehold(client).load(fake.speakers.map { it.host }).first { it.name == "Kusina Amp" }
        LinkplayExtras(client, amp).rename("Kitchen Amp")
        assertEquals("Kitchen Amp", client.probe(amp.host)?.name)
        assertFailsWith<IllegalArgumentException> { LinkplayExtras(client, amp).rename("") }
    }
}

class LinkplayInputTest {
    @Test
    fun `inputs switch with switchmode and missing ones are refused`() = runTest {
        val fake = FakeLinkplayHousehold()
        val client = LinkplayClient(fake)
        val amp = LinkplayHousehold(client).load(fake.speakers.map { it.host }).first { it.name == "Kusina Amp" }
        val x = LinkplayExtras(client, amp)
        x.switchInput(LinkplayInput.LINE_IN)
        assertTrue(fake.commands.any { it.endsWith("setPlayerCmd:switchmode:line-in") })
        assertFailsWith<IllegalStateException> { x.switchInput(LinkplayInput.OPTICAL) }
    }
}
