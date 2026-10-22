package com.timborja.tugma.bt.lab

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/** One application-level payload pulled out of a Bluetooth capture. */
data class CapturedPayload(
    val index: Int,
    /** Microseconds since 0 AD, as btsnoop stores them. */
    val timestampMicros: Long,
    /** True when the phone sent it. */
    val sent: Boolean,
    val connection: Int,
    val channel: Channel,
    val bytes: ByteArray,
) {
    sealed interface Channel {
        /** Classic Bluetooth RFCOMM (SPP-style) data on a DLCI. */
        data class Rfcomm(val cid: Int, val dlci: Int) : Channel { override fun toString() = "RFCOMM dlci $dlci (cid 0x%04x)".format(cid) }
        /** BLE GATT write/notify/indicate on an attribute handle. */
        data class Att(val handle: Int, val op: String) : Channel { override fun toString() = "GATT handle 0x%04x".format(handle) }
    }

    /** Groups payloads by connection, channel and direction for the stream list. */
    val streamKey: String get() = "conn 0x%03x · %s · %s".format(connection, channel, if (sent) "phone → device" else "device → phone")
}

/**
 * Reads Android's `btsnoop_hci.log` (btsnoop v1, HCI UART/H4 datalink 1002, or HCI un-encapsulated
 * 1001), reassembles ACL fragments and extracts RFCOMM UIH payloads and ATT write/notify values.
 * That's where vendor app traffic lives for most Bluetooth audio gear.
 */
object BtSnoop {
    private val MAGIC = "btsnoop\u0000".toByteArray(Charsets.US_ASCII)
    private const val DATALINK_H1 = 1001
    private const val DATALINK_H4 = 1002

    fun parse(file: ByteArray): List<CapturedPayload> {
        if (file.size < 16 || !file.copyOfRange(0, 8).contentEquals(MAGIC)) throw IOException("Not a btsnoop capture (missing \"btsnoop\" header)")
        val input = DataInputStream(ByteArrayInputStream(file))
        input.skipBytes(8)
        val version = input.readInt()
        val datalink = input.readInt()
        if (version != 1) throw IOException("Unsupported btsnoop version $version")
        if (datalink != DATALINK_H4 && datalink != DATALINK_H1) throw IOException("Unsupported datalink $datalink (expected 1001 or 1002)")

        val out = mutableListOf<CapturedPayload>()
        val partial = mutableMapOf<Pair<Int, Boolean>, ByteArray>() // (handle, received) → L2CAP bytes so far
        val expected = mutableMapOf<Pair<Int, Boolean>, Int>()
        while (true) {
            val record = try {
                val originalLength = input.readInt()
                val includedLength = input.readInt()
                val flags = input.readInt()
                input.readInt() // cumulative drops
                val ts = input.readLong()
                if (includedLength < 0 || includedLength > 65_535) throw IOException("Corrupt record length $includedLength")
                val data = ByteArray(includedLength).also { input.readFully(it) }
                Triple(flags, ts, data).also { originalLength.hashCode() }
            } catch (_: EOFException) {
                break
            }
            val (flags, ts, data) = record
            val received = flags and 1 != 0
            // H4 prefixes a packet-type byte (0x02 = ACL); H1 relies on the flags' command/data bit.
            val acl = when (datalink) {
                DATALINK_H4 -> if (data.isNotEmpty() && data[0].toInt() == 0x02) data.copyOfRange(1, data.size) else continue
                else -> if (flags and 2 == 0) data else continue
            }
            if (acl.size < 4) continue
            val handleFlags = u16le(acl, 0)
            val handle = handleFlags and 0x0FFF
            val pb = (handleFlags shr 12) and 0x3
            val aclLen = u16le(acl, 2)
            val chunk = acl.copyOfRange(4, minOf(acl.size, 4 + aclLen))
            val key = handle to received
            if (pb == 0x1) {
                // Continuation fragment.
                partial[key] = (partial[key] ?: continue) + chunk
            } else {
                partial[key] = chunk
                if (chunk.size >= 2) expected[key] = u16le(chunk, 0) + 4
            }
            val l2 = partial[key] ?: continue
            val need = expected[key] ?: continue
            if (l2.size < need) continue
            partial.remove(key)
            decodeL2cap(l2.copyOfRange(0, need), handle, !received, ts, out)
        }
        return out
    }

    private fun decodeL2cap(l2: ByteArray, handle: Int, sent: Boolean, ts: Long, out: MutableList<CapturedPayload>) {
        if (l2.size < 4) return
        val cid = u16le(l2, 2)
        val payload = l2.copyOfRange(4, l2.size)
        when {
            cid == 0x0004 -> att(payload)?.let { (h, op, v) -> out += CapturedPayload(out.size, ts, sent, handle, CapturedPayload.Channel.Att(h, op), v) }
            cid >= 0x0040 -> rfcomm(payload)?.let { (dlci, data) ->
                if (data.isNotEmpty()) out += CapturedPayload(out.size, ts, sent, handle, CapturedPayload.Channel.Rfcomm(cid, dlci), data)
            }
        }
    }

    /** ATT: 0x12 write request, 0x52 write command, 0x1b notification, 0x1d indication. */
    private fun att(p: ByteArray): Triple<Int, String, ByteArray>? {
        if (p.size < 3) return null
        val op = when (p[0].toInt() and 0xFF) {
            0x12 -> "write"; 0x52 -> "write-no-response"; 0x1b -> "notify"; 0x1d -> "indicate"; else -> return null
        }
        return Triple(u16le(p, 1), op, p.copyOfRange(3, p.size))
    }

    /**
     * RFCOMM frame: address (EA, C/R, DLCI), control, length (1 or 2 bytes, EA-terminated),
     * an optional credit byte on UIH frames with P/F set, payload, FCS. Only UIH data frames on
     * DLCI > 0 are returned (DLCI 0 is the multiplexer control channel).
     */
    internal fun rfcomm(p: ByteArray): Pair<Int, ByteArray>? {
        if (p.size < 4) return null
        val address = p[0].toInt() and 0xFF
        val dlci = address shr 2
        val control = p[1].toInt() and 0xFF
        val isUih = control and 0xEF == 0xEF
        if (!isUih || dlci == 0) return null
        var i = 2
        var length = (p[i].toInt() and 0xFF) shr 1
        if (p[i].toInt() and 1 == 0) {
            if (p.size < 5) return null
            length = length or ((p[i + 1].toInt() and 0xFF) shl 7)
            i += 2
        } else {
            i += 1
        }
        if (control and 0x10 != 0) i += 1 // credit byte (credit-based flow control)
        if (i + length > p.size - 1) return null
        return dlci to p.copyOfRange(i, i + length)
    }

    private fun u16le(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** Builds a minimal capture; used by tests and handy for checking an import works. */
    object Writer {
        fun file(records: List<Pair<Boolean, ByteArray>>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val d = java.io.DataOutputStream(out)
            d.write(MAGIC); d.writeInt(1); d.writeInt(DATALINK_H4)
            records.forEachIndexed { i, (received, h4) ->
                d.writeInt(h4.size); d.writeInt(h4.size); d.writeInt(if (received) 1 else 0); d.writeInt(0)
                d.writeLong(0x00E03AB44A676000L + i * 1000L)
                d.write(h4)
            }
            return out.toByteArray()
        }

        /** One H4 ACL packet carrying an RFCOMM UIH frame with [payload] on [dlci]. */
        fun rfcommPacket(handle: Int, cid: Int, dlci: Int, payload: ByteArray, credit: Boolean = false): ByteArray {
            val lengthBytes = if (payload.size < 128) byteArrayOf(((payload.size shl 1) or 1).toByte())
            else byteArrayOf((payload.size shl 1 and 0xFE).toByte(), (payload.size shr 7).toByte())
            val control = if (credit) 0xFF else 0xEF
            val frame = byteArrayOf(((dlci shl 2) or 0x03).toByte(), control.toByte()) + lengthBytes +
                (if (credit) byteArrayOf(0x01) else ByteArray(0)) + payload + byteArrayOf(0x55)
            return acl(handle, l2cap(cid, frame))
        }

        fun attPacket(handle: Int, op: Int, attHandle: Int, value: ByteArray): ByteArray =
            acl(handle, l2cap(0x0004, byteArrayOf(op.toByte(), attHandle.toByte(), (attHandle shr 8).toByte()) + value))

        fun l2cap(cid: Int, payload: ByteArray) =
            byteArrayOf(payload.size.toByte(), (payload.size shr 8).toByte(), cid.toByte(), (cid shr 8).toByte()) + payload

        fun acl(handle: Int, l2: ByteArray, pb: Int = 0x2) =
            byteArrayOf(0x02, handle.toByte(), ((handle shr 8) or (pb shl 4)).toByte(), l2.size.toByte(), (l2.size shr 8).toByte()) + l2
    }
}
