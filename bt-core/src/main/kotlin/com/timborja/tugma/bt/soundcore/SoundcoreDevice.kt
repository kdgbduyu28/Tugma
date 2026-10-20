/*
 * Soundcore packet bodies and device session, ported from OpenSCQ30
 * (https://github.com/Oppzippy/OpenSCQ30, GPL-3.0): the common/packet/inbound modules,
 * the common/packet/outbound modules, common/the structures modules and a3028/packets/state_update_packet.rs.
 */
package com.timborja.tugma.bt.soundcore

import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AmbientMode(val byte: Int, val label: String) {
    NOISE_CANCELING(0, "Noise cancelling"), TRANSPARENCY(1, "Transparency"), NORMAL(2, "Normal");
    companion object { fun of(b: Int) = entries.firstOrNull { it.byte == b } ?: NOISE_CANCELING }
}

enum class NoiseCancelingMode(val byte: Int, val label: String) {
    TRANSPORT(0, "Transport"), OUTDOOR(1, "Outdoor"), INDOOR(2, "Indoor"), CUSTOM(3, "Custom");
    companion object { fun of(b: Int) = entries.firstOrNull { it.byte == b } ?: TRANSPORT }
}

enum class TransparencyMode(val byte: Int, val label: String) {
    FULLY_TRANSPARENT(0, "Fully transparent"), VOCAL(1, "Vocal");
    companion object { fun of(b: Int) = entries.firstOrNull { it.byte == b } ?: FULLY_TRANSPARENT }
}

/** The classic 4-byte sound-mode block. Unknown bytes fall back to defaults, as in OpenSCQ30. */
data class SoundModes(
    val ambient: AmbientMode = AmbientMode.NOISE_CANCELING,
    val noiseCanceling: NoiseCancelingMode = NoiseCancelingMode.TRANSPORT,
    val transparency: TransparencyMode = TransparencyMode.FULLY_TRANSPARENT,
    /** 0–10, or 255 meaning "not set". */
    val customNoiseCanceling: Int = 0,
) {
    fun bytes(): ByteArray = byteArrayOf(ambient.byte.toByte(), noiseCanceling.byte.toByte(), transparency.byte.toByte(), customNoiseCanceling.toByte())

    companion object {
        fun parse(b: ByteArray, offset: Int = 0): SoundModes = SoundModes(
            AmbientMode.of(b[offset].u()),
            NoiseCancelingMode.of(b[offset + 1].u()),
            TransparencyMode.of(b[offset + 2].u()),
            b[offset + 3].u().let { if (it == 255) 255 else it.coerceIn(0, 10) },
        )
    }
}

data class Battery(val level: Int, val charging: Boolean) {
    /** Soundcore reports 0–5. */
    val percent: Int get() = (level.coerceIn(0, 5) * 20)
}

data class EqSetting(val presetId: Int, val tenths: List<List<Int>>) {
    val presetName: String get() = if (presetId == SoundcoreEq.CUSTOM_PRESET_ID) "Custom" else SoundcoreEq.preset(presetId)?.name ?: "Preset 0x%04x".format(presetId)
}

data class SoundcoreState(
    val connected: Boolean = false,
    val left: Battery? = null,
    val right: Battery? = null,
    val soundModes: SoundModes? = null,
    val eq: EqSetting? = null,
    val firmware: List<String> = emptyList(),
    val serial: String? = null,
    val error: String? = null,
    /** Packets we received but don't understand for this model, by command. */
    val unknownCommands: Set<String> = emptySet(),
)

/** Body parsers. Each returns null when the bytes don't fit the expected shape. */
object SoundcoreParsers {
    /** 0x0103: one level (headphones) or two (earbuds), optionally followed by two more. */
    fun batteryLevels(body: ByteArray): List<Int>? = when (body.size) {
        1, 2, 4 -> body.take(if (body.size == 1) 1 else 2).map { it.u() }
        else -> null
    }

    /** 0x0104: one or two charging flags. */
    fun charging(body: ByteArray): List<Boolean>? = when (body.size) {
        1, 2 -> body.map { it.u() != 0 }
        else -> null
    }

    /** 0x0105: one or two 5-char firmware versions (all 0x00/0xff = absent), then a 16-char serial. */
    fun serialAndFirmware(body: ByteArray): Pair<List<String>, String>? {
        if (body.size != 21 && body.size != 26) return null
        val versions = (0 until (body.size - 16) / 5).mapNotNull { i -> version(body, i * 5) }
        val serial = String(body, body.size - 16, 16, Charsets.US_ASCII)
        return versions to serial
    }

    /** 0x0601. */
    fun soundModes(body: ByteArray): SoundModes? = if (body.size == 4) SoundModes.parse(body) else null

    /** Preset id (u16 LE) + 8 bands per channel. */
    fun eq(body: ByteArray, offset: Int, channels: Int): EqSetting {
        val id = body[offset].u() or (body[offset + 1].u() shl 8)
        val tenths = (0 until channels).map { c -> (0 until 8).map { b -> SoundcoreEq.decodeGain(body[offset + 2 + c * 8 + b]) } }
        return EqSetting(id, tenths)
    }

    /**
     * Life Q30 (A3028) state, 0x0101: battery(2) eq(10) gender(1) age(1) hearId(21)
     * soundModes(4) firmware(5) serial(16), then optional extra fields.
     */
    fun a3028State(body: ByteArray): SoundcoreState? {
        if (body.size < 60) return null
        val battery = Battery(body[0].u(), body[1].u() != 0)
        val eq = eq(body, 2, 1)
        val modes = SoundModes.parse(body, 35)
        val firmware = listOfNotNull(version(body, 39))
        val serial = String(body, 44, 16, Charsets.US_ASCII)
        return SoundcoreState(connected = true, left = battery, soundModes = modes, eq = eq, firmware = firmware, serial = serial)
    }

    private fun version(body: ByteArray, at: Int): String? {
        val slice = body.copyOfRange(at, at + 5)
        if (slice.all { it == 0.toByte() } || slice.all { it == 0xff.toByte() }) return null
        return String(slice, Charsets.US_ASCII)
    }
}

object SoundcoreRequests {
    fun state() = SoundcoreFrames.outbound(Commands.STATE)
    fun batteryLevel() = SoundcoreFrames.outbound(Commands.BATTERY_LEVEL)
    fun batteryCharging() = SoundcoreFrames.outbound(Commands.BATTERY_CHARGING)
    fun serialAndFirmware() = SoundcoreFrames.outbound(Commands.SERIAL_AND_FIRMWARE)
    fun setSoundModes(m: SoundModes) = SoundcoreFrames.outbound(Commands.SET_SOUND_MODES, m.bytes())

    /** Preset id + the same 8 gains for every channel the model has. */
    fun setEqualizer(presetId: Int, tenths: List<Int>, channels: Int): SoundcorePacket {
        require(tenths.size == 8) { "Soundcore EQ has 8 bands" }
        val gains = ByteArray(8) { SoundcoreEq.encodeGain(tenths[it]) }
        val body = byteArrayOf((presetId and 0xFF).toByte(), (presetId ushr 8 and 0xFF).toByte()) +
            (0 until channels).fold(ByteArray(0)) { acc, _ -> acc + gains }
        return SoundcoreFrames.outbound(Commands.SET_EQUALIZER, body)
    }
}

/**
 * One connection to a Soundcore device. Sends the requests the model supports, keeps
 * [state] up to date from replies and push updates, and logs every packet to [log].
 */
class SoundcoreDevice(
    val model: SoundcoreModel,
    private val transport: BtTransport,
    private val log: CommandLog? = null,
    private val label: String = "bt",
) {
    private val decoder = SoundcoreDecoder(Direction.INBOUND, model.checksum)
    private val _state = MutableStateFlow(SoundcoreState())
    val state: StateFlow<SoundcoreState> = _state.asStateFlow()
    private var reader: Job? = null

    fun start(scope: CoroutineScope) {
        reader = scope.launch {
            _state.update { it.copy(connected = true, error = null) }
            launch { requestAll() }
            try {
                while (isActive) decoder.feed(transport.read()).forEach(::handle)
            } catch (e: Exception) {
                _state.update { it.copy(connected = false, error = e.message ?: "Disconnected") }
            }
        }
    }

    suspend fun requestAll() {
        send(SoundcoreRequests.state())
        send(SoundcoreRequests.serialAndFirmware())
        if (model.battery != BatteryKind.NONE) {
            send(SoundcoreRequests.batteryLevel())
            send(SoundcoreRequests.batteryCharging())
        }
    }

    suspend fun setSoundModes(modes: SoundModes) {
        check(model.soundModesV1) { "Sound modes aren't ported for ${model.name}" }
        send(SoundcoreRequests.setSoundModes(modes))
        // The device acks with an empty packet; reflect the change right away.
        _state.update { it.copy(soundModes = modes) }
    }

    suspend fun setEqualizer(presetId: Int, tenths: List<Int>) {
        check(model.eq != EqKind.NONE) { "Equalizer isn't ported for ${model.name}" }
        send(SoundcoreRequests.setEqualizer(presetId, tenths, model.eq.channels))
        _state.update { it.copy(eq = EqSetting(presetId, List(model.eq.channels) { tenths })) }
    }

    fun close() {
        reader?.cancel()
        runCatching { transport.close() }
        _state.update { it.copy(connected = false) }
    }

    private suspend fun send(packet: SoundcorePacket) {
        val bytes = SoundcoreFrames.encode(packet, model.checksum)
        record("→", packet, bytes)
        transport.write(bytes)
    }

    internal fun handle(p: SoundcorePacket) {
        record("←", p, null)
        val body = p.body
        if (body.isEmpty()) return // acknowledgement of a write
        when (p.command) {
            Commands.STATE -> if (model.fullState && model.id == "A3028") {
                SoundcoreParsers.a3028State(body)?.let { s -> _state.update { s.copy(unknownCommands = it.unknownCommands) } }
            } else {
                unknown(p)
            }
            Commands.BATTERY_LEVEL -> SoundcoreParsers.batteryLevels(body)?.let { levels ->
                _state.update {
                    it.copy(
                        left = Battery(levels[0], it.left?.charging ?: false),
                        right = levels.getOrNull(1)?.let { r -> Battery(r, it.right?.charging ?: false) } ?: it.right,
                    )
                }
            } ?: unknown(p)
            Commands.BATTERY_CHARGING -> SoundcoreParsers.charging(body)?.let { flags ->
                _state.update {
                    it.copy(
                        left = (it.left ?: Battery(0, false)).copy(charging = flags[0]),
                        right = flags.getOrNull(1)?.let { c -> (it.right ?: Battery(0, false)).copy(charging = c) } ?: it.right,
                    )
                }
            } ?: unknown(p)
            Commands.SERIAL_AND_FIRMWARE -> SoundcoreParsers.serialAndFirmware(body)?.let { (fw, serial) ->
                _state.update { it.copy(firmware = fw, serial = serial) }
            } ?: unknown(p)
            Commands.SOUND_MODES -> if (model.soundModesV1) {
                SoundcoreParsers.soundModes(body)?.let { m -> _state.update { it.copy(soundModes = m) } } ?: unknown(p)
            } else {
                unknown(p)
            }
            else -> unknown(p)
        }
    }

    private fun unknown(p: SoundcorePacket) {
        _state.update { it.copy(unknownCommands = it.unknownCommands + p.command.toString()) }
    }

    private fun record(arrow: String, p: SoundcorePacket, raw: ByteArray?) {
        val l = log ?: return
        l.record(
            LogEntry(
                id = l.nextId(),
                timestampMillis = System.currentTimeMillis(),
                method = "BT $arrow",
                url = "rfcomm://$label  cmd ${p.command}",
                requestHeaders = emptyMap(),
                requestBody = if (arrow == "→") (raw ?: ByteArray(0)).toHex() else "",
                status = null,
                responseBody = if (arrow == "←") p.body.toHex() else null,
                durationMillis = 0,
            ),
        )
    }
}

internal fun Byte.u(): Int = toInt() and 0xFF
