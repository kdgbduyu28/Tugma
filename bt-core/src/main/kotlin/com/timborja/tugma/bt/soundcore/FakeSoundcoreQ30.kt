package com.timborja.tugma.bt.soundcore

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * A pretend Life Q30 on the other end of an RFCOMM socket, for tests and demo mode. It answers
 * the same packets a real one does (state layout from OpenSCQ30's A3028 test vector).
 */
class FakeSoundcoreQ30(private val latencyMillis: Long = 0) : BtTransport {
    var battery = Battery(4, charging = false)
    var modes = SoundModes(AmbientMode.NORMAL, NoiseCancelingMode.TRANSPORT, TransparencyMode.VOCAL, 0)
    var eq = EqSetting(0x0000, listOf(List(8) { 0 }))
    val firmware = "02.30"
    val serial = "3029086EC82F12AC"
    val received = mutableListOf<SoundcorePacket>()

    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = SoundcoreDecoder(Direction.OUTBOUND)
    private var closed = false

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw java.io.IOException("socket closed")
        if (latencyMillis > 0) delay(latencyMillis)
        decoder.feed(bytes).forEach { p ->
            received += p
            respond(p)
        }
    }

    override suspend fun read(): ByteArray = toPhone.receive()

    override fun close() {
        closed = true
        toPhone.close()
    }

    /** Simulates the earbuds pushing an update, e.g. after a button press. */
    fun push(command: Command, body: ByteArray) {
        toPhone.trySend(SoundcoreFrames.encode(SoundcorePacket(Direction.INBOUND, command, body)))
    }

    private fun reply(command: Command, body: ByteArray) = push(command, body)

    private fun respond(p: SoundcorePacket) {
        when (p.command) {
            Commands.STATE -> reply(Commands.STATE, stateBody())
            Commands.SERIAL_AND_FIRMWARE -> reply(Commands.SERIAL_AND_FIRMWARE, (firmware + serial).toByteArray(Charsets.US_ASCII))
            Commands.BATTERY_LEVEL -> reply(Commands.BATTERY_LEVEL, byteArrayOf(battery.level.toByte()))
            Commands.BATTERY_CHARGING -> reply(Commands.BATTERY_CHARGING, byteArrayOf(if (battery.charging) 1 else 0))
            Commands.SET_SOUND_MODES -> {
                modes = SoundModes.parse(p.body)
                reply(Commands.SET_SOUND_MODES, ByteArray(0)) // ack
            }
            Commands.SET_EQUALIZER -> {
                eq = SoundcoreParsers.eq(p.body, 0, 1)
                reply(Commands.SET_EQUALIZER, ByteArray(0))
            }
        }
    }

    private fun stateBody(): ByteArray {
        val eqBytes = byteArrayOf((eq.presetId and 0xFF).toByte(), (eq.presetId ushr 8).toByte()) +
            ByteArray(8) { SoundcoreEq.encodeGain(eq.tenths[0][it]) }
        return byteArrayOf(battery.level.toByte(), if (battery.charging) 1 else 0) +
            eqBytes +
            byteArrayOf(0, 0) + // gender, age range
            ByteArray(21) + // HearID: off
            modes.bytes() +
            firmware.toByteArray(Charsets.US_ASCII) +
            serial.toByteArray(Charsets.US_ASCII)
    }
}
