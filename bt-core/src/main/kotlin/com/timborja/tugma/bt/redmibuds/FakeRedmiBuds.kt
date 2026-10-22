package com.timborja.tugma.bt.redmibuds

import com.timborja.tugma.bt.soundcore.BtTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Pretend Redmi Buds 5 Pro for tests and demo mode. It runs the real challenge handshake and
 * refuses everything until the phone answers its challenge correctly.
 */
class FakeRedmiBuds(
    private val latencyMillis: Long = 0,
    private val budsChallenge: ByteArray = ByteArray(16) { (it * 7 + 3).toByte() },
) : BtTransport {
    var ambient = AmbientSound.OFF
    var ancStrength = AncStrength.BALANCED
    var transparencyStrength = TransparencyStrength.REGULAR
    var eqPreset = EqPreset.STANDARD
    var eqCurve = List(10) { 0 }
    var earDetection = true
    val toggles = mutableMapOf(Toggle.AUTO_ANSWER to false, Toggle.DOUBLE_CONNECTION to true, Toggle.ADAPTIVE_ANC to true, Toggle.ADAPTIVE_SOUND to false)
    var left = BudBattery(80, false)
    var right = BudBattery(75, false)
    var case = BudBattery(50, true)

    var authenticated = false
        private set
    var findRequests = 0
        private set
    val received = mutableListOf<RedmiMessage>()

    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = RedmiDecoder()
    private var seq = 0x40
    private var closed = false

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw java.io.IOException("socket closed")
        if (latencyMillis > 0) delay(latencyMillis)
        decoder.feed(bytes).forEach { received += it; respond(it) }
    }

    override suspend fun read(): ByteArray = toPhone.receive()

    override fun close() {
        closed = true
        toPhone.close()
    }

    /** Pushes a battery/ambient status report, as the earbuds do when something changes. */
    fun pushStatus() = send(RedmiMessage(MessageType.EARBUDS_NOTIFY, Opcode.REPORT_STATUS, seq++, byteArrayOf(0x04, 0x00, enc(left), enc(right), enc(case), 0x02, 0x04, ambient.code.toByte())))

    private fun send(m: RedmiMessage) {
        toPhone.trySend(m.encode())
    }

    private fun reply(to: RedmiMessage, payload: ByteArray) = send(RedmiMessage(MessageType.RESPONSE, to.opcode, to.sequence, payload))

    private fun respond(m: RedmiMessage) {
        if (m.type == MessageType.RESPONSE) {
            when (m.opcode) {
                Opcode.AUTH_CHALLENGE -> {
                    val expected = byteArrayOf(0x01) + RedmiBudsAuth.response(budsChallenge.copyOf())
                    if (m.payload.contentEquals(expected)) {
                        authenticated = true
                        send(RedmiMessage(MessageType.EARBUDS_REQUEST, Opcode.AUTH_CONFIRM, seq++, byteArrayOf(0x01)))
                    }
                }
                else -> Unit
            }
            return
        }
        when (m.opcode) {
            Opcode.AUTH_CHALLENGE -> {
                reply(m, byteArrayOf(0x01) + RedmiBudsAuth.response(m.payload.copyOfRange(1, 17)))
                send(RedmiMessage(MessageType.EARBUDS_REQUEST, Opcode.AUTH_CHALLENGE, seq++, byteArrayOf(0x01) + budsChallenge))
            }
            Opcode.AUTH_CONFIRM -> reply(m, byteArrayOf(0x01))
            else -> if (!authenticated) reply(m, byteArrayOf(0x01)) else when (m.opcode) {
                Opcode.GET_DEVICE_INFO -> reply(m, byteArrayOf(0x05, 0x01, 0x12, 0x34, 0x01, 0x02, 0x05, 0x03, 0x27, 0x17, 0x50, 0x6a, 0x04, 0x07, enc(left), enc(right), enc(case)))
                Opcode.GET_DEVICE_RUN_INFO -> reply(m, byteArrayOf(0x02, 0x09, ambient.code.toByte(), 0x02, 0x0A, if (earDetection) 0 else 1))
                Opcode.GET_CONFIG -> config(m)
                Opcode.ANC -> {
                    when (m.payload.getOrNull(1)?.toInt()) {
                        0x04 -> ambient = AmbientSound.entries.first { it.code == m.payload[2].toInt() }
                        0x06 -> earDetection = m.payload[2].toInt() == 0
                    }
                    reply(m, ByteArray(0))
                }
                Opcode.SET_CONFIG -> {
                    setConfig(m.payload)
                    reply(m, ByteArray(0))
                }
                else -> reply(m, ByteArray(0))
            }
        }
    }

    private fun config(m: RedmiMessage) {
        val id = m.payload[1].toInt() and 0xFF
        val body: ByteArray = when (id) {
            RedmiConfig.EQ_PRESET -> byteArrayOf(0x03, 0x00, id.toByte(), eqPreset.code.toByte())
            RedmiConfig.EFFECT_STRENGTH -> if (ambient == AmbientSound.TRANSPARENCY) {
                byteArrayOf(0x04, 0x00, id.toByte(), RedmiConfig.STRENGTH_TRANSPARENCY.toByte(), transparencyStrength.code.toByte())
            } else {
                byteArrayOf(0x04, 0x00, id.toByte(), RedmiConfig.STRENGTH_ANC.toByte(), ancStrength.code.toByte())
            }
            RedmiConfig.EQ_CURVE -> {
                // Reply layout: levels start at index 12, every third byte.
                val out = ByteArray(12 + 3 * 10)
                out[0] = (out.size - 1).toByte(); out[2] = id.toByte()
                eqCurve.forEachIndexed { i, db -> out[12 + 3 * i] = RedmiConfig.encodeLevel(db) }
                out
            }
            else -> Toggle.entries.firstOrNull { it.config == id }?.let { t -> byteArrayOf(0x03, 0x00, id.toByte(), if (toggles[t] == true) 1 else 0) }
                ?: byteArrayOf(0x02, 0x00, id.toByte())
        }
        reply(m, body)
    }

    private fun setConfig(p: ByteArray) {
        val id = p[2].toInt() and 0xFF
        when (id) {
            RedmiConfig.EQ_PRESET -> eqPreset = EqPreset.entries.first { it.code == (p[3].toInt() and 0xFF) }
            RedmiConfig.EFFECT_STRENGTH -> if (p[3].toInt() == RedmiConfig.STRENGTH_ANC) {
                ancStrength = AncStrength.entries.first { it.code == p[4].toInt() }
            } else {
                transparencyStrength = TransparencyStrength.entries.first { it.code == p[4].toInt() }
            }
            RedmiConfig.EQ_CURVE -> eqCurve = (0 until 10).map { RedmiConfig.decodeLevel(p[9 + 3 * it]) }
            0x09 -> if (p[3].toInt() == 1) findRequests++
            else -> Toggle.entries.firstOrNull { it.config == id }?.let { toggles[it] = p[3].toInt() == 1 }
        }
    }

    private fun enc(b: BudBattery): Byte = ((b.level and 0x7F) or if (b.charging) 0x80 else 0).toByte()
}
