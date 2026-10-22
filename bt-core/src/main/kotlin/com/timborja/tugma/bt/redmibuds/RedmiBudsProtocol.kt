/*
 * Redmi Buds protocol, ported to Kotlin from Gadgetbridge
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge), service/devices/redmibuds and
 * devices/redmibuds. Original Copyright (C) 2024 Jonathan Gobbo and Gadgetbridge contributors.
 *
 * This file is licensed under the GNU Affero General Public License v3.0 or later, like the
 * original, and is distributed as part of Tugma (GPL-3.0-or-later) under section 13 of both licences.
 */
package com.timborja.tugma.bt.redmibuds

import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

object RedmiBudsUuids {
    /** RFCOMM service the earbuds' control channel listens on. */
    val CONTROL: UUID = UUID.fromString("0000fd2d-0000-1000-8000-00805f9b34fb")
}

enum class MessageType(val code: Int) {
    PHONE_REQUEST(0xC4), RESPONSE(0x04), EARBUDS_REQUEST(0xC0), EARBUDS_NOTIFY(0xC7), UNKNOWN(0xFF);

    /** Requests carry only a sequence number before the payload; responses also carry a status byte. */
    val isRequest: Boolean get() = code and 0x40 != 0

    companion object { fun of(b: Int) = entries.firstOrNull { it.code == b } ?: UNKNOWN }
}

enum class Opcode(val code: Int) {
    GET_DEVICE_INFO(0x02), ANC(0x08), GET_DEVICE_RUN_INFO(0x09), REPORT_STATUS(0x0E),
    AUTH_CHALLENGE(0x50), AUTH_CONFIRM(0x51), SET_CONFIG(0xF2), GET_CONFIG(0xF3), NOTIFY_CONFIG(0xF4), UNKNOWN(0xFF);

    companion object { fun of(b: Int) = entries.firstOrNull { it.code == b } ?: UNKNOWN }
}

/**
 * `fe dc ba` · type · opcode · u16 BE length · [status (responses)] · sequence · payload · `ef`.
 * Length counts the payload plus the sequence byte (and the status byte for responses).
 */
class RedmiMessage(val type: MessageType, val opcode: Opcode, val sequence: Int, val payload: ByteArray, val status: Int = 0) {
    fun encode(): ByteArray {
        val extra = if (type.isRequest) 1 else 2
        val length = payload.size + extra
        val out = ByteArray(8 + length)
        HEADER.copyInto(out, 0)
        out[3] = type.code.toByte()
        out[4] = opcode.code.toByte()
        out[5] = (length ushr 8).toByte()
        out[6] = length.toByte()
        var i = 7
        if (!type.isRequest) out[i++] = status.toByte()
        out[i++] = sequence.toByte()
        payload.copyInto(out, i)
        out[out.lastIndex] = TRAILER
        return out
    }

    override fun toString(): String = "$type $opcode seq=$sequence [${payload.joinToString(" ") { "%02x".format(it) }}]"

    override fun equals(other: Any?): Boolean = other is RedmiMessage && other.type == type && other.opcode == opcode &&
        other.sequence == sequence && other.payload.contentEquals(payload) && other.status == status

    override fun hashCode(): Int = payload.contentHashCode() * 31 + opcode.hashCode()

    companion object {
        val HEADER = byteArrayOf(0xfe.toByte(), 0xdc.toByte(), 0xba.toByte())
        const val TRAILER: Byte = 0xef.toByte()
    }
}

/** Reassembles messages from RFCOMM reads using the length field, resyncing on garbage. */
class RedmiDecoder {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<RedmiMessage> {
        buffer += chunk
        val out = mutableListOf<RedmiMessage>()
        while (true) {
            val start = (0..buffer.size - 3).firstOrNull { i ->
                buffer[i] == RedmiMessage.HEADER[0] && buffer[i + 1] == RedmiMessage.HEADER[1] && buffer[i + 2] == RedmiMessage.HEADER[2]
            }
            if (start == null) {
                buffer = buffer.copyOfRange(maxOf(0, buffer.size - 2), buffer.size)
                return out
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 8) return out
            val type = MessageType.of(buffer[3].toInt() and 0xFF)
            val length = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[6].toInt() and 0xFF)
            val extra = if (type.isRequest) 1 else 2
            val total = 8 + length
            if (length < extra || length > 2048) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < total) return out
            if (buffer[total - 1] != RedmiMessage.TRAILER) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            val status = if (type.isRequest) 0 else buffer[7].toInt() and 0xFF
            val seq = buffer[7 + extra - 1].toInt() and 0xFF
            out += RedmiMessage(type, Opcode.of(buffer[4].toInt() and 0xFF), seq, buffer.copyOfRange(7 + extra, total - 1), status)
            buffer = buffer.copyOfRange(total, buffer.size)
        }
    }
}

/**
 * The earbuds' challenge-response cipher (a SAFER+-style block cipher), ported from
 * Gadgetbridge's protocol/Authentication.java and AuthData.java. Tested against vectors
 * generated from the original Java.
 */
object RedmiBudsAuth {
    private const val PATTERN = 0x9999
    private const val BLOCK = 16
    private val SEQ = intArrayOf(0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33)
    private val COEFFICIENTS = arrayOf(
        intArrayOf(2, 1, 1, 1, 4, 2, 1, 1, 2, 2, 4, 2, 4, 4, 16, 8),
        intArrayOf(2, 1, 1, 1, 4, 2, 1, 1, 1, 1, 2, 1, 2, 2, 8, 4),
        intArrayOf(1, 1, 4, 2, 2, 2, 4, 2, 16, 8, 4, 4, 2, 1, 1, 1),
        intArrayOf(1, 1, 4, 2, 1, 1, 2, 1, 8, 4, 2, 2, 2, 1, 1, 1),
        intArrayOf(16, 8, 2, 2, 4, 2, 4, 4, 1, 1, 4, 2, 1, 1, 2, 1),
        intArrayOf(8, 4, 1, 1, 2, 1, 2, 2, 1, 1, 4, 2, 1, 1, 2, 1),
        intArrayOf(2, 2, 4, 2, 4, 4, 16, 8, 2, 1, 1, 1, 4, 2, 1, 1),
        intArrayOf(1, 1, 2, 1, 2, 2, 8, 4, 2, 1, 1, 1, 4, 2, 1, 1),
        intArrayOf(4, 2, 4, 4, 16, 8, 2, 2, 1, 1, 2, 1, 1, 1, 4, 2),
        intArrayOf(2, 1, 2, 2, 8, 4, 1, 1, 1, 1, 2, 1, 1, 1, 4, 2),
        intArrayOf(4, 4, 16, 8, 1, 1, 2, 1, 4, 2, 1, 1, 4, 2, 2, 2),
        intArrayOf(2, 2, 8, 4, 1, 1, 2, 1, 4, 2, 1, 1, 2, 1, 1, 1),
        intArrayOf(1, 1, 2, 1, 1, 1, 4, 2, 4, 4, 16, 8, 2, 2, 4, 2),
        intArrayOf(1, 1, 2, 1, 1, 1, 4, 2, 2, 2, 8, 4, 1, 1, 2, 1),
        intArrayOf(4, 2, 1, 1, 2, 1, 1, 1, 4, 2, 2, 2, 16, 8, 4, 4),
        intArrayOf(4, 2, 1, 1, 2, 1, 1, 1, 2, 1, 1, 1, 8, 4, 2, 2),
    )

    private val P257 = BigInteger.valueOf(257)
    private val B45 = BigInteger.valueOf(45)

    /** expTab[i] = 45^i mod 257 (with 256 → 0, i.e. index 128 → 0). */
    private val expTab = IntArray(256) { i -> if (i == 128) 0 else B45.modPow(BigInteger.valueOf(i.toLong()), P257).toInt() }

    private val logTab = IntArray(256).also { t ->
        t[0] = 128
        for (i in 1 until 256) {
            val v = B45.modPow(BigInteger.valueOf(i.toLong()), P257).toInt()
            if (v != 256) t[v] = i
        }
    }

    private val bias = Array(16) { i ->
        IntArray(16) { j ->
            val exponent = 17 * (i + 2) + (j + 1)
            val inner = B45.modPow(BigInteger.valueOf(exponent.toLong()), P257)
            val v = B45.modPow(inner, P257).toInt()
            if (v == 256) 0 else v
        }
    }

    private fun on(i: Int) = (1 shl i) and PATTERN != 0

    private fun keySchedule(challenge: ByteArray): Array<IntArray> {
        val init = IntArray(16) { challenge[it].toInt() and 0xFF }
        init[15] = init[15] xor 6
        val keys = arrayOfNulls<IntArray>(17)
        keys[0] = init.copyOf()
        val register = IntArray(17)
        for (i in 0 until 16) register[i] = init[i]
        register[16] = init.fold(0) { acc, b -> acc xor b }
        for (k in 1 until 17) {
            for (i in 0 until 17) register[i] = ((register[i] ushr 5) or (register[i] shl 3)) and 0xFF
            keys[k] = IntArray(16) { i -> (register[(k + i) % 17] + bias[k - 1][i]) and 0xFF }
        }
        @Suppress("UNCHECKED_CAST")
        return keys as Array<IntArray>
    }

    private fun encrypt(plain: IntArray, keys: Array<IntArray>): ByteArray {
        val c = plain.copyOf()
        for (round in 0 until 8) {
            if (round == 2) for (i in 0 until BLOCK) c[i] = if (on(i)) c[i] xor plain[i] else (c[i] + plain[i]) and 0xFF
            val k1 = keys[round * 2]
            for (i in 0 until BLOCK) c[i] = if (on(i)) c[i] xor k1[i] else (c[i] + k1[i]) and 0xFF
            for (i in 0 until BLOCK) c[i] = if (on(i)) expTab[c[i]] else logTab[c[i]]
            val k2 = keys[round * 2 + 1]
            for (i in 0 until BLOCK) c[i] = if (on(i)) (k2[i] + c[i]) and 0xFF else k2[i] xor c[i]
            val copy = c.copyOf()
            for (i in 0 until BLOCK) {
                var sum = 0
                for (j in 0 until BLOCK) sum += COEFFICIENTS[i][j] * signed(copy[j])
                c[i] = sum and 0xFF
            }
        }
        val k = keys[16]
        for (i in 0 until BLOCK) c[i] = if (on(i)) k[i] xor c[i] else (k[i] + c[i]) and 0xFF
        return ByteArray(BLOCK) { c[it].toByte() }
    }

    /** Java multiplies coefficients by signed bytes; the low 8 bits come out the same either way, but match it exactly. */
    private fun signed(b: Int) = b.toByte().toInt()

    fun response(challenge: ByteArray): ByteArray {
        require(challenge.size == BLOCK) { "Challenge must be 16 bytes" }
        return encrypt(SEQ, keySchedule(challenge))
    }

    fun randomChallenge(): ByteArray = ByteArray(BLOCK).also { SecureRandom().nextBytes(it) }
}
