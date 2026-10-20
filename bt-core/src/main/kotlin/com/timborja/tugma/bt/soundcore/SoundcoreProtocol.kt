/*
 * Soundcore RFCOMM protocol, ported to Kotlin from OpenSCQ30
 * (https://github.com/Oppzippy/OpenSCQ30, GPL-3.0, Kyle Scheuing and contributors).
 * Packet layout, checksum, command ids and field encodings follow
 * lib/src/devices/soundcore/common/packet.rs and the structures modules.
 */
package com.timborja.tugma.bt.soundcore

import java.io.Closeable
import java.util.UUID

/** A byte stream to a Bluetooth device: an RFCOMM socket in the app, a fake in tests. */
interface BtTransport : Closeable {
    suspend fun write(bytes: ByteArray)
    /** Returns the next chunk of bytes (any size); throws when the connection closes. */
    suspend fun read(): ByteArray
}

enum class Direction(val header: ByteArray) {
    OUTBOUND(byteArrayOf(0x08, 0xee.toByte(), 0x00, 0x00, 0x00)),
    INBOUND(byteArrayOf(0x09, 0xff.toByte(), 0x00, 0x00, 0x01)),
}

/** Two-byte command id, e.g. 0x01 0x01 = state. */
data class Command(val a: Int, val b: Int) {
    override fun toString(): String = "%02x%02x".format(a, b)
}

data class SoundcorePacket(val direction: Direction, val command: Command, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SoundcorePacket && other.direction == direction && other.command == command && other.body.contentEquals(body)

    override fun hashCode(): Int = (direction.hashCode() * 31 + command.hashCode()) * 31 + body.contentHashCode()

    override fun toString(): String = "$direction ${command} [${body.toHex()}]"
}

object Commands {
    val STATE = Command(0x01, 0x01)
    val TWS_STATUS = Command(0x01, 0x02)
    val BATTERY_LEVEL = Command(0x01, 0x03)
    val BATTERY_CHARGING = Command(0x01, 0x04)
    val SERIAL_AND_FIRMWARE = Command(0x01, 0x05)
    val SOUND_MODES = Command(0x06, 0x01)
    val SET_SOUND_MODES = Command(0x06, 0x81)
    val SET_EQUALIZER = Command(0x02, 0x81)
}

object SoundcoreFrames {
    /** Sum of every byte, wrapping at 256. */
    fun checksum(bytes: ByteArray, length: Int = bytes.size): Byte {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return sum.toByte()
    }

    /** 5-byte direction, 2-byte command, little-endian u16 total length, body, optional checksum. */
    fun encode(packet: SoundcorePacket, withChecksum: Boolean = true): ByteArray {
        val length = 5 + 2 + 2 + packet.body.size + if (withChecksum) 1 else 0
        val out = ByteArray(length)
        packet.direction.header.copyInto(out, 0)
        out[5] = packet.command.a.toByte()
        out[6] = packet.command.b.toByte()
        out[7] = (length and 0xFF).toByte()
        out[8] = (length ushr 8 and 0xFF).toByte()
        packet.body.copyInto(out, 9)
        if (withChecksum) out[length - 1] = checksum(out, length - 1)
        return out
    }

    fun outbound(command: Command, body: ByteArray = ByteArray(0)) = SoundcorePacket(Direction.OUTBOUND, command, body)
}

/**
 * Splits a byte stream into packets. Keeps partial packets between [feed] calls and skips
 * forward to the next header after garbage or a bad checksum.
 */
class SoundcoreDecoder(private val direction: Direction = Direction.INBOUND, private val withChecksum: Boolean = true) {
    private var buffer = ByteArray(0)
    var droppedBytes = 0
        private set

    fun feed(chunk: ByteArray): List<SoundcorePacket> {
        buffer += chunk
        val out = mutableListOf<SoundcorePacket>()
        while (true) {
            val start = indexOfHeader()
            if (start < 0) {
                // Keep a possible partial header at the end.
                val keep = minOf(buffer.size, direction.header.size - 1)
                droppedBytes += buffer.size - keep
                buffer = buffer.copyOfRange(buffer.size - keep, buffer.size)
                return out
            }
            if (start > 0) {
                droppedBytes += start
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            if (buffer.size < 9) return out
            val length = (buffer[7].toInt() and 0xFF) or ((buffer[8].toInt() and 0xFF) shl 8)
            val minimum = 9 + if (withChecksum) 1 else 0
            if (length < minimum || length > MAX_PACKET) {
                drop(1)
                continue
            }
            if (buffer.size < length) return out
            if (withChecksum && SoundcoreFrames.checksum(buffer, length - 1) != buffer[length - 1]) {
                drop(1)
                continue
            }
            val bodyEnd = length - if (withChecksum) 1 else 0
            out += SoundcorePacket(direction, Command(buffer[5].toInt() and 0xFF, buffer[6].toInt() and 0xFF), buffer.copyOfRange(9, bodyEnd))
            buffer = buffer.copyOfRange(length, buffer.size)
        }
    }

    private fun drop(n: Int) {
        droppedBytes += n
        buffer = buffer.copyOfRange(n, buffer.size)
    }

    private fun indexOfHeader(): Int {
        val h = direction.header
        outer@ for (i in 0..buffer.size - h.size) {
            for (j in h.indices) if (buffer[i + j] != h[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val MAX_PACKET = 4096
    }
}

object SoundcoreUuids {
    /** Plain Serial Port Profile; older models use this. */
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val VENDOR_BASE: UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e700000")

    /** Soundcore's own RFCOMM service: the vendor base with the low 20 bits varying per model. */
    fun isVendor(uuid: UUID): Boolean =
        uuid.mostSignificantBits == VENDOR_BASE.mostSignificantBits &&
            (uuid.leastSignificantBits and 0xFFFFF.inv().toLong()) == VENDOR_BASE.leastSignificantBits

    /** Prefers the vendor service, then SPP. */
    fun pick(advertised: List<UUID>): UUID = advertised.firstOrNull(::isVendor) ?: SPP

    /** The vendor UUID's last four hex digits are the model number on the models we've seen (e.g. …7b3116 → 3116). */
    fun modelHint(advertised: List<UUID>): String? =
        advertised.firstOrNull(::isVendor)?.toString()?.takeLast(4)?.uppercase()
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
