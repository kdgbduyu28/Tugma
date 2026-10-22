package com.timborja.tugma.bt.lab

import com.timborja.tugma.bt.redmibuds.MessageType
import com.timborja.tugma.bt.redmibuds.Opcode
import com.timborja.tugma.bt.redmibuds.RedmiMessage
import com.timborja.tugma.bt.soundcore.Commands
import com.timborja.tugma.bt.soundcore.Direction
import com.timborja.tugma.bt.soundcore.SoundcoreFrames
import com.timborja.tugma.bt.soundcore.SoundcorePacket
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexTest {
    @Test
    fun `parses the usual notations`() {
        val expected = byteArrayOf(0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x01)
        listOf("fe dc ba 01", "FEDCBA01", "0xfe, 0xdc, 0xba, 0x01", "fe:dc:ba:1").forEach { assertContentEquals(expected, Hex.parse(it), it) }
        assertEquals(0, Hex.parse("  ").size)
        assertFailsWith<IllegalArgumentException> { Hex.parse("fe zz") }
    }

    @Test
    fun `dump shows offsets and ascii`() {
        val d = Hex.dump("Tugma!".toByteArray() + byteArrayOf(0, 1))
        assertTrue(d.startsWith("0000  54 75 67 6d 61 21 00 01"))
        assertTrue(d.trimEnd().endsWith("Tugma!.."))
    }
}

class FramingAnalyzerTest {
    @Test
    fun `recognises Soundcore framing`() {
        val packets = listOf(
            SoundcorePacket(Direction.INBOUND, Commands.BATTERY_LEVEL, byteArrayOf(3, 4)),
            SoundcorePacket(Direction.INBOUND, Commands.SOUND_MODES, byteArrayOf(2, 0, 1, 0)),
            SoundcorePacket(Direction.INBOUND, Commands.SERIAL_AND_FIRMWARE, "02.300123456789ABCDEF".toByteArray()),
        ).map { SoundcoreFrames.encode(it) }
        val g = FramingAnalyzer.analyse(packets)
        assertContentEquals(byteArrayOf(0x09, 0xff.toByte(), 0x00, 0x00, 0x01), g.header)
        assertEquals(FramingGuess.LengthField(7, bigEndian = false, adjustment = 0), g.lengthField)
        assertEquals(FramingGuess.Checksum.SUM8, g.checksum)
        assertTrue(5 in g.commandOffsets || 6 in g.commandOffsets)
    }

    @Test
    fun `recognises Redmi Buds framing`() {
        val packets = listOf(
            RedmiMessage(MessageType.RESPONSE, Opcode.GET_CONFIG, 3, byteArrayOf(0x03, 0x00, 0x07, 0x05)),
            RedmiMessage(MessageType.EARBUDS_NOTIFY, Opcode.REPORT_STATUS, 9, byteArrayOf(0x04, 0x00, 0x50, 0x4b, 0x32, 0x02, 0x04, 0x01)),
            RedmiMessage(MessageType.RESPONSE, Opcode.GET_DEVICE_RUN_INFO, 4, byteArrayOf(0x02, 0x09, 0x01)),
        ).map { it.encode() }
        val g = FramingAnalyzer.analyse(packets)
        assertContentEquals(byteArrayOf(0xfe.toByte(), 0xdc.toByte(), 0xba.toByte()), g.header)
        assertEquals(0xef.toByte(), g.trailer)
        // Length counts payload + seq (+ status): it isn't a fixed offset from total size across types,
        // so the analyser must not claim one that only fits by accident.
        g.lengthField?.let { lf -> packets.forEach { p -> assertEquals(p.size - lf.adjustment, ((p[lf.offset].toInt() and 0xFF) shl 8) or (p[lf.offset + 1].toInt() and 0xFF)) } }
    }

    @Test
    fun `nothing in common gives an honest empty guess`() {
        val g = FramingAnalyzer.analyse(listOf(byteArrayOf(1, 2, 3), byteArrayOf(9, 8, 7, 6, 5)))
        assertEquals(0, g.header.size)
        assertNull(g.trailer)
        assertTrue(g.describe().contains("2 packets"))
    }
}

class BtSnoopTest {
    private val soundcore = SoundcoreFrames.encode(SoundcorePacket(Direction.OUTBOUND, Commands.STATE, ByteArray(0)))
    private val reply = SoundcoreFrames.encode(SoundcorePacket(Direction.INBOUND, Commands.BATTERY_LEVEL, byteArrayOf(2)))

    @Test
    fun `extracts rfcomm payloads with direction, including credit frames`() {
        val file = BtSnoop.Writer.file(
            listOf(
                false to BtSnoop.Writer.rfcommPacket(0x0003, 0x0041, 18, soundcore),
                true to BtSnoop.Writer.rfcommPacket(0x0003, 0x0041, 18, reply, credit = true),
                false to byteArrayOf(0x01, 0x03, 0x0c, 0x00), // HCI command: ignored
            ),
        )
        val out = BtSnoop.parse(file)
        assertEquals(2, out.size)
        assertTrue(out[0].sent)
        assertContentEquals(soundcore, out[0].bytes)
        assertEquals(CapturedPayload.Channel.Rfcomm(0x0041, 18), out[0].channel)
        assertTrue(!out[1].sent)
        assertContentEquals(reply, out[1].bytes)
    }

    @Test
    fun `reassembles fragmented ACL and long rfcomm lengths`() {
        val big = ByteArray(300) { it.toByte() }
        val whole = BtSnoop.Writer.rfcommPacket(0x0005, 0x0042, 4, big)
        // Split the L2CAP part into start + continuation fragments.
        val l2 = whole.copyOfRange(5, whole.size)
        val start = BtSnoop.Writer.acl(0x0005, l2.copyOfRange(0, 100)).let { it.copyOf().also { b -> b[3] = 100; b[4] = 0 } }
        val cont = BtSnoop.Writer.acl(0x0005, l2.copyOfRange(100, l2.size), pb = 0x1)
        val out = BtSnoop.parse(BtSnoop.Writer.file(listOf(true to start, true to cont)))
        assertEquals(1, out.size)
        assertContentEquals(big, out.single().bytes)
    }

    @Test
    fun `extracts GATT writes and notifications`() {
        val file = BtSnoop.Writer.file(
            listOf(
                false to BtSnoop.Writer.attPacket(0x0040, 0x52, 0x002a, byteArrayOf(0x11, 0x22)),
                true to BtSnoop.Writer.attPacket(0x0040, 0x1b, 0x002c, byteArrayOf(0x33)),
                true to BtSnoop.Writer.attPacket(0x0040, 0x0a, 0x002c, byteArrayOf(0x44)), // read request: ignored
            ),
        )
        val out = BtSnoop.parse(file)
        assertEquals(listOf("write-no-response", "notify"), out.map { (it.channel as CapturedPayload.Channel.Att).op })
        assertEquals(0x002a, (out[0].channel as CapturedPayload.Channel.Att).handle)
    }

    @Test
    fun `rejects other files`() {
        assertFailsWith<IOException> { BtSnoop.parse("hello world, not a capture".toByteArray()) }
    }

    @Test
    fun `captured soundcore stream feeds the framing analyser`() {
        val packets = (1..4).map { SoundcoreFrames.encode(SoundcorePacket(Direction.INBOUND, Commands.BATTERY_LEVEL, byteArrayOf(it.toByte()))) } +
            SoundcoreFrames.encode(SoundcorePacket(Direction.INBOUND, Commands.SOUND_MODES, byteArrayOf(0, 1, 1, 0)))
        val file = BtSnoop.Writer.file(packets.map { true to BtSnoop.Writer.rfcommPacket(3, 0x41, 18, it) })
        val g = FramingAnalyzer.analyse(BtSnoop.parse(file).map { it.bytes })
        assertEquals(FramingGuess.Checksum.SUM8, g.checksum)
        assertEquals(5, g.header.size)
    }
}
