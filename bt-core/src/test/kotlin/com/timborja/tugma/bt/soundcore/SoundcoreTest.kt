package com.timborja.tugma.bt.soundcore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte vectors below are copied from OpenSCQ30's unit tests (GPL-3.0), several captured from real devices. */
private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun inbound(vararg v: Int): SoundcorePacket = SoundcoreDecoder().feed(bytes(*v)).single()

class SoundcoreFramesTest {
    @Test
    fun `checksum wraps at 256`() {
        assertEquals(0, SoundcoreFrames.checksum(ByteArray(0)).toInt())
        assertEquals(3, SoundcoreFrames.checksum(bytes(1, 2)).toInt())
        assertEquals(1, SoundcoreFrames.checksum(bytes(0xff, 0x02)).toInt())
    }

    @Test
    fun `request packets match OpenSCQ30`() {
        assertContentEquals(bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x01, 0x01, 0x0a, 0x00, 0x02), SoundcoreFrames.encode(SoundcoreRequests.state()))
        assertContentEquals(bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x01, 0x03, 0x0a, 0x00, 0x04), SoundcoreFrames.encode(SoundcoreRequests.batteryLevel()))
    }

    @Test
    fun `set sound modes matches OpenSCQ30`() {
        val normal = SoundModes(AmbientMode.NORMAL, NoiseCancelingMode.TRANSPORT, TransparencyMode.VOCAL, 0)
        assertContentEquals(
            bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x06, 0x81, 0x0e, 0x00, 0x02, 0x00, 0x01, 0x00, 0x8e),
            SoundcoreFrames.encode(SoundcoreRequests.setSoundModes(normal)),
        )
        val anc = SoundModes(AmbientMode.NOISE_CANCELING, NoiseCancelingMode.OUTDOOR, TransparencyMode.VOCAL, 0)
        assertContentEquals(
            bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x06, 0x81, 0x0e, 0x00, 0x00, 0x01, 0x01, 0x00, 0x8d),
            SoundcoreFrames.encode(SoundcoreRequests.setSoundModes(anc)),
        )
    }

    @Test
    fun `set equalizer matches OpenSCQ30 for custom, preset and two channels`() {
        assertContentEquals(
            bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x02, 0x81, 0x14, 0x00, 0xfe, 0xfe, 0x3c, 0xb4, 0x8f, 0xa0, 0x8e, 0xb4, 0x74, 0x88, 0xe6),
            SoundcoreFrames.encode(SoundcoreRequests.setEqualizer(0xfefe, listOf(-60, 60, 23, 40, 22, 60, -4, 16), 1)),
        )
        assertContentEquals(
            bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x02, 0x81, 0x14, 0x00, 0x00, 0x00, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x4d),
            SoundcoreFrames.encode(SoundcoreRequests.setEqualizer(0x0000, List(8) { 0 }, 1)),
        )
        val trebleReducer = listOf(0, 0, 0, -20, -30, -40, -40, -60)
        assertContentEquals(
            bytes(0x08, 0xee, 0x00, 0x00, 0x00, 0x02, 0x81, 0x14, 0x00, 0x15, 0x00, 0x78, 0x78, 0x78, 0x64, 0x5a, 0x50, 0x50, 0x3c, 0xa4),
            SoundcoreFrames.encode(SoundcoreRequests.setEqualizer(0x15, trebleReducer, 1)),
        )
        assertContentEquals(
            bytes(
                0x08, 0xee, 0x00, 0x00, 0x00, 0x02, 0x81, 0x1C, 0x00, 0x15, 0x00, 0x78, 0x78, 0x78,
                0x64, 0x5a, 0x50, 0x50, 0x3c, 0x78, 0x78, 0x78, 0x64, 0x5a, 0x50, 0x50, 0x3c, 0xae,
            ),
            SoundcoreFrames.encode(SoundcoreRequests.setEqualizer(0x15, trebleReducer, 2)),
        )
    }

    @Test
    fun `gain encoding clamps to the device range`() {
        assertEquals(0x78, SoundcoreEq.encodeGain(0).toInt() and 0xFF)
        assertEquals(0, SoundcoreEq.encodeGain(-500).toInt() and 0xFF)
        assertEquals(254, SoundcoreEq.encodeGain(500).toInt() and 0xFF)
        assertEquals(-60, SoundcoreEq.decodeGain(0x3c.toByte()))
    }
}

class SoundcoreDecoderTest {
    private val q30Battery = bytes(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x03, 0x0b, 0x00, 0x02, 0x1a)

    @Test
    fun `reassembles packets split across reads and skips garbage`() {
        val d = SoundcoreDecoder()
        assertTrue(d.feed(bytes(0x55, 0x66) + q30Battery.copyOfRange(0, 4)).isEmpty())
        val out = d.feed(q30Battery.copyOfRange(4, q30Battery.size) + q30Battery)
        assertEquals(2, out.size)
        assertEquals(Commands.BATTERY_LEVEL, out[0].command)
        assertEquals(2, d.droppedBytes)
    }

    @Test
    fun `bad checksum is skipped, the next good packet still parses`() {
        val bad = q30Battery.copyOf().also { it[it.lastIndex] = 0x00 }
        val out = SoundcoreDecoder().feed(bad + q30Battery)
        assertEquals(1, out.size)
    }

    @Test
    fun `packets without checksum (Motion+)`() {
        val p = SoundcorePacket(Direction.INBOUND, Commands.BATTERY_LEVEL, bytes(3))
        val out = SoundcoreDecoder(withChecksum = false).feed(SoundcoreFrames.encode(p, withChecksum = false))
        assertEquals(listOf(p), out)
    }
}

class SoundcoreParsersTest {
    @Test
    fun `battery levels single and dual`() {
        assertEquals(listOf(2), SoundcoreParsers.batteryLevels(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x03, 0x0b, 0x00, 0x02, 0x1a).body))
        assertEquals(listOf(3, 4), SoundcoreParsers.batteryLevels(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x03, 0x0c, 0x00, 0x03, 0x04, 0x20).body))
        assertEquals(listOf(4, 5), SoundcoreParsers.batteryLevels(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x03, 0x0e, 0x00, 0x04, 0x05, 0x01, 0x02, 0x27).body))
    }

    @Test
    fun `charging flags`() {
        assertEquals(listOf(true, false), SoundcoreParsers.charging(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x04, 0x0c, 0x00, 0x01, 0x00, 0x1b).body))
        assertEquals(listOf(true), SoundcoreParsers.charging(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x04, 0x0b, 0x00, 0x01, 0x1a).body))
    }

    @Test
    fun `sound modes, with invalid bytes falling back to defaults`() {
        val m = SoundcoreParsers.soundModes(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x06, 0x01, 0x0e, 0x00, 0x02, 0x02, 0x01, 0x00, 0x23).body)!!
        assertEquals(AmbientMode.NORMAL, m.ambient)
        assertEquals(NoiseCancelingMode.INDOOR, m.noiseCanceling)
        val badAmbient = SoundcoreParsers.soundModes(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x06, 0x01, 0x0e, 0x00, 0x03, 0x02, 0x01, 0x00, 0x24).body)!!
        assertEquals(AmbientMode.NOISE_CANCELING, badAmbient.ambient)
        val badNc = SoundcoreParsers.soundModes(inbound(0x09, 0xff, 0x00, 0x00, 0x01, 0x06, 0x01, 0x0e, 0x00, 0x02, 0x04, 0x01, 0x00, 0x25).body)!!
        assertEquals(NoiseCancelingMode.TRANSPORT, badNc.noiseCanceling)
    }

    @Test
    fun `serial and dual firmware`() {
        val p = inbound(
            0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x05, 0x24, 0x00, 0x31, 0x32, 0x2e, 0x33, 0x34,
            0x32, 0x33, 0x2e, 0x34, 0x35, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0xc9,
        )
        val (fw, serial) = SoundcoreParsers.serialAndFirmware(p.body)!!
        assertEquals(listOf("12.34", "23.45"), fw)
        assertEquals("0123456789ABCDEF", serial)
    }

    @Test
    fun `life Q30 state packet captured from a device`() {
        val p = inbound(
            0x09, 0xff, 0x00, 0x00, 0x01, 0x01, 0x01, 0x46, 0x00, 0x05, 0x00, 0x01, 0x00, 0x3c,
            0xb4, 0x8f, 0xa0, 0x8e, 0xb4, 0x74, 0x88, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x02, 0x00, 0x01, 0x00, 0x30, 0x32, 0x2e, 0x33, 0x30, 0x33, 0x30, 0x32,
            0x39, 0x30, 0x38, 0x36, 0x45, 0x43, 0x38, 0x32, 0x46, 0x31, 0x32, 0x41, 0x43, 0x35,
        )
        val s = SoundcoreParsers.a3028State(p.body)!!
        assertEquals(Battery(5, false), s.left)
        assertEquals(100, s.left!!.percent)
        assertEquals(SoundModes(AmbientMode.NORMAL, NoiseCancelingMode.TRANSPORT, TransparencyMode.VOCAL, 0), s.soundModes)
        assertEquals(1, s.eq!!.presetId)
        assertEquals(listOf(-60, 60, 23, 40, 22, 60, -4, 16), s.eq!!.tenths.single())
        assertEquals(listOf("02.30"), s.firmware)
        assertEquals("3029086EC82F12AC", s.serial)
    }
}

class SoundcoreModelsTest {
    @Test
    fun `vendor uuid detection and model hint`() {
        val motion = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3116")
        assertTrue(SoundcoreUuids.isVendor(motion))
        assertFalse(SoundcoreUuids.isVendor(SoundcoreUuids.SPP))
        assertEquals(motion, SoundcoreUuids.pick(listOf(SoundcoreUuids.SPP, motion)))
        assertEquals(SoundcoreUuids.SPP, SoundcoreUuids.pick(emptyList()))
        assertEquals("3116", SoundcoreUuids.modelHint(listOf(motion)))
        assertEquals("A3116", SoundcoreModels.guess(null, "3116")?.id)
    }

    @Test
    fun `guesses models from bluetooth names`() {
        assertEquals("A3028", SoundcoreModels.guess("Soundcore Life Q30", null)?.id)
        assertEquals("A3947", SoundcoreModels.guess("soundcore Liberty 4 NC", null)?.id)
        assertEquals("A3953", SoundcoreModels.guess("Soundcore Liberty 4", null)?.id)
        assertEquals("A3949", SoundcoreModels.guess("Soundcore R50i", null)?.id)
        assertNull(SoundcoreModels.guess("Pixel Buds", null))
        assertTrue(SoundcoreModels.looksLikeSoundcore("Soundcore Something New"))
        assertTrue(SoundcoreModels.all.map { it.id }.toSet().size == SoundcoreModels.all.size)
    }
}

class SoundcoreDeviceTest {
    @Test
    fun `session reads state, then changes modes and eq`() = runTest {
        val fake = FakeSoundcoreQ30()
        val device = SoundcoreDevice(SoundcoreModels.byId("A3028")!!, fake)
        device.start(backgroundScope)
        val s = device.state.first { it.serial != null && it.soundModes != null }
        assertEquals("3029086EC82F12AC", s.serial)
        assertEquals(Battery(4, false), device.state.value.left)

        device.setSoundModes(SoundModes(AmbientMode.NOISE_CANCELING, NoiseCancelingMode.INDOOR, TransparencyMode.VOCAL, 0))
        device.setEqualizer(0x0002, SoundcoreEq.preset(0x0002)!!.tenths)
        assertEquals(AmbientMode.NOISE_CANCELING, fake.modes.ambient)
        assertEquals(NoiseCancelingMode.INDOOR, fake.modes.noiseCanceling)
        assertEquals(2, fake.eq.presetId)
        assertEquals(listOf(40, 30, 10, 0, 0, 0, 0, 0), fake.eq.tenths.single())

        // The read loop runs in backgroundScope, which advanceUntilIdle() skips; wait on state instead.
        fake.push(Commands.BATTERY_CHARGING, bytes(1))
        assertEquals(true, device.state.first { it.left?.charging == true }.left?.charging)
        assertTrue(device.state.value.unknownCommands.isEmpty())
        device.close()
    }

    @Test
    fun `models without ported features refuse those writes`() = runTest {
        val device = SoundcoreDevice(SoundcoreModels.byId("A3947")!!, FakeSoundcoreQ30())
        val e = runCatching { device.setSoundModes(SoundModes()) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue("ported" in e.message!!)
    }
}
