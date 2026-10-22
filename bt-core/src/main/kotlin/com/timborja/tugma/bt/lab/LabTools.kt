package com.timborja.tugma.bt.lab

/** Hex input/output for the protocol lab. */
object Hex {
    /**
     * Accepts "fe dc ba", "FEDCBA", "0xfe,0xdc", "fe:dc:ba" and mixes. Throws with the offending
     * token when something isn't hex.
     */
    fun parse(text: String): ByteArray {
        val cleaned = text.replace("0x", " ", ignoreCase = true).replace(Regex("[,:;_\\-\\s]+"), " ").trim()
        if (cleaned.isEmpty()) return ByteArray(0)
        val tokens = cleaned.split(' ').flatMap { t -> if (t.length > 2 && t.length % 2 == 0) t.chunked(2) else listOf(t) }
        return ByteArray(tokens.size) { i ->
            val t = tokens[i]
            require(t.length in 1..2 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "\"$t\" isn't a hex byte" }
            t.toInt(16).toByte()
        }
    }

    fun format(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /** Classic 16-bytes-per-line dump with offsets and printable ASCII. */
    fun dump(bytes: ByteArray): String = buildString {
        for (offset in bytes.indices step 16) {
            val line = bytes.copyOfRange(offset, minOf(bytes.size, offset + 16))
            append("%04x  ".format(offset))
            append(line.joinToString(" ") { "%02x".format(it) }.padEnd(16 * 3 - 1))
            append("  ")
            line.forEach { b -> val c = b.toInt() and 0xFF; append(if (c in 0x20..0x7e) c.toChar() else '.') }
            appendLine()
        }
    }
}

/** What [FramingAnalyzer] thinks a protocol's packets look like. Every field is a guess with evidence. */
data class FramingGuess(
    val samples: Int,
    /** Bytes every packet starts with. */
    val header: ByteArray,
    /** Offset of a 16-bit length field and how it relates to the packet size, if one fits every sample. */
    val lengthField: LengthField?,
    /** Byte every packet ends with, if constant. */
    val trailer: Byte?,
    /** Checksum over everything before the last byte, if one matches every sample. */
    val checksum: Checksum?,
    /** Bytes after the header that vary a lot, likely a command or sequence number. */
    val commandOffsets: List<Int>,
) {
    data class LengthField(val offset: Int, val bigEndian: Boolean, val adjustment: Int) {
        /** "u16 BE at 5 = total length − 8". */
        override fun toString(): String = "u16 ${if (bigEndian) "BE" else "LE"} at offset $offset = total length" +
            if (adjustment == 0) "" else if (adjustment > 0) " − $adjustment" else " + ${-adjustment}"
    }

    enum class Checksum(val label: String) { SUM8("8-bit sum"), XOR8("8-bit XOR"), SUM8_AFTER_HEADER("8-bit sum after header"), XOR8_AFTER_HEADER("8-bit XOR after header") }

    fun describe(): String = buildString {
        appendLine("$samples packets analysed")
        appendLine("Header: ${if (header.isEmpty()) "none in common" else Hex.format(header)}")
        appendLine("Length: ${lengthField ?: "no 16-bit length field fits every packet"}")
        appendLine("Trailer: ${trailer?.let { "%02x".format(it) } ?: "none"}")
        appendLine("Checksum: ${checksum?.label ?: "no simple checksum fits"}")
        if (commandOffsets.isNotEmpty()) appendLine("Varying bytes after header (command/sequence?): offsets ${commandOffsets.joinToString(", ")}")
    }

    override fun equals(other: Any?): Boolean = other is FramingGuess && other.describe() == describe()
    override fun hashCode(): Int = describe().hashCode()
}

/**
 * Guesses the framing of an unknown protocol from captured packets: common header, a 16-bit
 * length field, constant trailer, simple checksums. It checks the shapes Soundcore and Redmi Buds
 * actually use, which covers a lot of cheap Bluetooth audio gear.
 */
object FramingAnalyzer {
    fun analyse(packets: List<ByteArray>): FramingGuess {
        val p = packets.filter { it.isNotEmpty() }
        if (p.isEmpty()) return FramingGuess(0, ByteArray(0), null, null, null, emptyList())
        val minLen = p.minOf { it.size }

        var headerLen = 0
        while (headerLen < minLen && p.all { it[headerLen] == p[0][headerLen] }) headerLen++
        // A header that covers whole packets isn't a header.
        if (headerLen == minLen) headerLen = maxOf(0, minLen - 1)
        val header = p[0].copyOfRange(0, headerLen)

        val length = findLength(p, minLen)
        val trailer = p[0].last().takeIf { t -> p.size > 1 && p.all { it.last() == t } }
        val checksum = if (trailer != null) null else findChecksum(p, headerLen)

        val varying = (headerLen until minOf(minLen, headerLen + 4)).filter { i ->
            length?.let { i != it.offset && i != it.offset + 1 } ?: true
        }.filter { i -> p.map { it[i] }.toSet().size > 1 }

        return FramingGuess(p.size, header, length, trailer, checksum, varying)
    }

    private fun findLength(p: List<ByteArray>, minLen: Int): FramingGuess.LengthField? {
        if (p.map { it.size }.toSet().size < 2 && p.size > 1) {
            // All the same size: any match would be a coincidence, so still try but require exact fit.
        }
        for (offset in 0 until minLen - 1) {
            for (be in listOf(false, true)) {
                val adjustments = p.map { b ->
                    val v = if (be) ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
                    else (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
                    b.size - v
                }.toSet()
                if (adjustments.size == 1) {
                    val adj = adjustments.single()
                    if (adj in -2..16) return FramingGuess.LengthField(offset, be, adj)
                }
            }
        }
        return null
    }

    private fun findChecksum(p: List<ByteArray>, headerLen: Int): FramingGuess.Checksum? {
        fun sum(b: ByteArray, from: Int) = (from until b.size - 1).fold(0) { a, i -> a + (b[i].toInt() and 0xFF) } and 0xFF
        fun xor(b: ByteArray, from: Int) = (from until b.size - 1).fold(0) { a, i -> a xor (b[i].toInt() and 0xFF) }
        val checks = listOf(
            FramingGuess.Checksum.SUM8 to { b: ByteArray -> sum(b, 0) },
            FramingGuess.Checksum.XOR8 to { b: ByteArray -> xor(b, 0) },
            FramingGuess.Checksum.SUM8_AFTER_HEADER to { b: ByteArray -> sum(b, headerLen) },
            FramingGuess.Checksum.XOR8_AFTER_HEADER to { b: ByteArray -> xor(b, headerLen) },
        )
        return checks.firstOrNull { (_, f) -> p.size >= 2 && p.all { b -> b.size >= 2 && f(b) == (b.last().toInt() and 0xFF) } }?.first
    }
}
