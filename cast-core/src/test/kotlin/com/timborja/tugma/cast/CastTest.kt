package com.timborja.tugma.cast

import com.timborja.tugma.cast.demo.FakeCastHousehold
import com.timborja.tugma.core.InMemoryCommandLog
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.upnp.NotLocalNetworkException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastFramesTest {
    @Test
    fun `encodes the CastMessage protobuf exactly`() {
        val m = CastMessage("sender-0", "receiver-0", "a", "{}")
        val expected = byteArrayOf(0x08, 0x00, 0x12, 0x08) + "sender-0".toByteArray() +
            byteArrayOf(0x1A, 0x0A) + "receiver-0".toByteArray() +
            byteArrayOf(0x22, 0x01) + "a".toByteArray() +
            byteArrayOf(0x28, 0x00, 0x32, 0x02) + "{}".toByteArray()
        assertContentEquals(expected, CastFrames.encode(m))
        val framed = CastFrames.frame(m)
        assertContentEquals(byteArrayOf(0, 0, 0, expected.size.toByte()), framed.copyOfRange(0, 4))
    }

    @Test
    fun `round trips long payloads with multi-byte varints and utf8`() {
        val payload = """{"type":"SET_VOLUME","note":"${"ñ".repeat(300)}"}"""
        val m = CastMessage("sender-0", "web-12", CastNamespaces.RECEIVER, payload)
        val back = CastFrames.decode(CastFrames.encode(m))
        assertEquals(m, back)
        assertEquals("SET_VOLUME", back.type)
    }

    @Test
    fun `skips unknown fields and rejects truncation`() {
        val withExtra = CastFrames.encode(CastMessage("s", "d", "n", "{}")) + byteArrayOf(0x40, 0x05) // field 8 varint
        assertEquals("n", CastFrames.decode(withExtra).namespace)
        assertFailsWith<java.io.IOException> { CastFrames.decode(byteArrayOf(0x12, 0x10, 0x41)) }
    }
}

class CastJsonTest {
    @Test
    fun `parses receiver and media status`() {
        val receiver = Json.parseToJsonElement(
            """{"requestId":1,"status":{"applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver",
            "isIdleScreen":false,"namespaces":[{"name":"urn:x-cast:com.google.cast.media"}],"sessionId":"abc",
            "statusText":"Casting","transportId":"web-7"}],"volume":{"controlType":"attenuation","level":0.35,
            "muted":false,"stepInterval":0.05}},"type":"RECEIVER_STATUS"}""",
        ).jsonObject
        val r = CastJson.receiverStatus(receiver)
        assertEquals(0.35, r.volumeLevel)
        assertEquals(false, r.muted)
        assertEquals("web-7", r.mediaApp?.transportId)

        val media = Json.parseToJsonElement(
            """{"type":"MEDIA_STATUS","requestId":2,"status":[{"mediaSessionId":3,"playerState":"BUFFERING",
            "media":{"metadata":{"metadataType":3,"title":"Kathang Isip","artist":"Ben&Ben"}}}]}""",
        ).jsonObject
        val ms = CastJson.mediaStatus(media)!!
        assertEquals(3, ms.mediaSessionId)
        assertEquals("Kathang Isip", ms.title)
        assertEquals("Ben&Ben", ms.artist)
        assertEquals(TransportState.TRANSITIONING, CastJson.transport(ms.playerState))
        assertNull(CastJson.mediaStatus(Json.parseToJsonElement("""{"type":"MEDIA_STATUS","status":[]}""").jsonObject))
    }

    @Test
    fun `txt records and groups`() {
        val d = CastDevice.fromTxt(
            mapOf("id" to "abc123".toByteArray(), "fn" to "Kusina".toByteArray(), "md" to "Google Cast Group".toByteArray(), "rs" to null),
            "Google-Cast-Group-abc123", "192.168.1.60", 32012,
        )
        assertEquals("abc123", d.id)
        assertEquals("Kusina", d.name)
        assertTrue(d.isGroup)
        assertEquals("Speaker group from Google Home (read-only)", d.toSummary().roleLabel)
        val bare = CastDevice.fromTxt(emptyMap(), "Living-Room-123", "192.168.1.61", 8009)
        assertEquals("Living-Room", bare.name)
        assertEquals("Living-Room-123", bare.id)
    }
}

class CastPlaybackTest {
    private val fake = FakeCastHousehold()
    private val log = InMemoryCommandLog()
    private val client = CastClient(fake, log, timeoutMillis = 2_000)
    private fun playback(name: String): CastPlayback {
        val d = fake.devices.first { it.info.name == name }.info
        return CastPlayback(client, d.host, d.port)
    }

    @Test
    fun `status answers heartbeat and reads volume and media`() = runTest {
        val s = playback("Kwarto Speaker").status()
        assertEquals(25, s.volume)
        assertEquals(false, s.muted)
        assertEquals(TransportState.PLAYING, s.transport)
        assertEquals("Demo Radio", s.title)
        assertEquals("Tugma FM", s.artist)
        assertTrue("PONG" in fake.received, "heartbeat PING must be answered")
        assertTrue(log.entries.value.any { it.method == "CAST" && it.requestBody.contains("GET_STATUS") })
    }

    @Test
    fun `volume and mute`() = runTest {
        val p = playback("Kwarto Speaker")
        p.setVolume(60)
        p.setMuted(true)
        val s = p.status()
        assertEquals(60, s.volume)
        assertEquals(true, s.muted)
    }

    @Test
    fun `pause play and stop through the media namespace`() = runTest {
        val p = playback("Buong Bahay")
        assertEquals(TransportState.PAUSED, p.status().transport)
        p.play()
        assertEquals(TransportState.PLAYING, p.status().transport)
        p.next()
        assertTrue(p.status().title!!.contains("skipped 1"))
        p.stop()
        assertEquals(TransportState.UNKNOWN, p.status().transport, "app still running but nothing loaded")
    }

    @Test
    fun `apps without the media namespace refuse playback but allow volume`() = runTest {
        val p = playback("Sala TV")
        val s = p.status()
        assertEquals("Casting: Demo Music App", s.title)
        val e = assertFailsWith<CastException> { p.pause() }
        assertTrue("doesn't accept playback control" in e.message!!)
        p.setVolume(10)
        assertEquals(10, p.status().volume)
    }

    @Test
    fun `idle device reports nothing casting`() = runTest {
        val p = playback("Opisina Display")
        assertEquals(TransportState.NO_MEDIA, p.status().transport)
        assertEquals("Nothing is casting to this device", assertFailsWith<CastException> { p.play() }.message)
    }

    @Test
    fun `tls connector refuses public hosts`() = runTest {
        assertFailsWith<NotLocalNetworkException> { TlsCastConnector().connect("8.8.8.8", 8009) }
    }
}

class CastPlayUrlTest {
    private val fake = FakeCastHousehold()
    private val client = CastClient(fake, null, timeoutMillis = 2_000)
    private fun playback(name: String): CastPlayback {
        val d = fake.devices.first { it.info.name == name }.info
        return CastPlayback(client, d.host, d.port)
    }

    @Test
    fun `playUrl launches the default media receiver when needed, then loads`() = runTest {
        val display = playback("Opisina Display")
        display.playUrl("https://radio.example/live.aac", "Example FM")
        assertTrue("LAUNCH" in fake.received)
        assertTrue("LOAD" in fake.received)
        assertEquals("https://radio.example/live.aac", fake.lastLoaded)
        val s = display.status()
        assertEquals(TransportState.PLAYING, s.transport)
        assertEquals("Example FM", s.title)
    }

    @Test
    fun `playUrl reuses a running default media receiver`() = runTest {
        fake.received.clear()
        playback("Kwarto Speaker").playUrl("http://radio.example/a.mp3", "A", live = false)
        assertTrue("LAUNCH" !in fake.received)
        assertEquals("http://radio.example/a.mp3", fake.lastLoaded)
    }
}

class CastProbeTest {
    @Test
    fun `probe finds a Cast device by address and ignores other hosts`() = runTest {
        val fake = FakeCastHousehold()
        val client = CastClient(fake, null, timeoutMillis = 2_000)
        val d = client.probe("192.168.50.41")
        assertEquals("ip:192.168.50.41:8009", d?.id)
        assertEquals(CastDevice.BY_IP_MODEL, d?.model)
        assertEquals(null, client.probe("192.168.50.99"))
    }
}

class CastStopTest {
    @Test
    fun `stopCasting closes the app with its session id`() = runTest {
        val fake = FakeCastHousehold()
        val client = CastClient(fake, null, timeoutMillis = 2_000)
        val d = fake.devices.first { it.info.name == "Kwarto Speaker" }.info
        val p = CastPlayback(client, d.host, d.port)
        p.stopCasting()
        assertEquals(TransportState.NO_MEDIA, p.status().transport)
        assertTrue(runCatching { p.stopCasting() }.isFailure)
    }
}
