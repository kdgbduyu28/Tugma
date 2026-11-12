/*
 * Redmi Buds models, settings and session, ported to Kotlin from Gadgetbridge
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge): RedmiBudsProtocol.java, the
 * devices/redmibuds coordinators and prefs enums. Original Copyright (C) 2024 Jonathan Gobbo
 * and Gadgetbridge contributors.
 *
 * This file is licensed under the GNU Affero General Public License v3.0 or later, like the
 * original, and is distributed as part of Tugma (GPL-3.0-or-later) under section 13 of both licences.
 */
package com.timborja.tugma.bt.redmibuds

import com.timborja.tugma.bt.soundcore.BtTransport
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AmbientSound(val code: Int, val label: String) { OFF(0x00, "Off"), NOISE_CANCELLING(0x01, "Noise cancelling"), TRANSPARENCY(0x02, "Transparency") }
enum class AncStrength(val code: Int, val label: String) { BALANCED(0x00, "Balanced"), LIGHT(0x01, "Light"), DEEP(0x02, "Deep"), ADAPTIVE(0x03, "Adaptive") }
enum class TransparencyStrength(val code: Int, val label: String) { REGULAR(0x00, "Regular"), VOICE(0x01, "Voice"), AMBIENT(0x02, "Ambient") }
enum class EqPreset(val code: Int, val label: String) {
    STANDARD(0x00, "Standard"), BALANCED(0x15, "Balanced"), TREBLE(0x06, "Treble"), BASS(0x05, "Bass"),
    VOICE(0x01, "Voice"), VOLUME(0x07, "Volume boost"), CUSTOM(0x0A, "Custom"),
}

/** Boolean settings, by their config id. */
enum class Toggle(val config: Int, val label: String) {
    AUTO_ANSWER(0x03, "Auto-answer calls"), DOUBLE_CONNECTION(0x04, "Connect to two devices"),
    ADAPTIVE_ANC(0x25, "Adaptive noise cancelling"), ADAPTIVE_SOUND(0x29, "Adaptive sound"),
}

enum class BudSide { LEFT, RIGHT }

/** Which touch gesture; the code is the record id inside the GESTURES config. */
enum class TapType(val code: Int, val label: String) { SINGLE(0x04, "Single tap"), DOUBLE(0x01, "Double tap"), TRIPLE(0x02, "Triple tap"), LONG(0x03, "Long press") }

enum class GestureAction(val code: Int, val label: String) {
    NONE(0x08, "Nothing"), PLAY_PAUSE(0x01, "Play / pause"), PREVIOUS_TRACK(0x02, "Previous track"),
    NEXT_TRACK(0x03, "Next track"), VOLUME_UP(0x04, "Volume up"), VOLUME_DOWN(0x05, "Volume down"),
}

enum class LongGestureAction(val code: Int, val label: String) {
    NONE(0x08, "Nothing"), VOICE_ASSISTANT(0x00, "Voice assistant"), AMBIENT_SOUND_CONTROL(0x06, "Noise control"),
}

/** Which modes a long press cycles through when it's set to noise control. */
enum class AmbientCycle(val code: Int, val label: String) {
    NOISE_CANCELLING_OFF(0x03, "Noise cancelling ↔ Off"), TRANSPARENCY_OFF(0x05, "Transparency ↔ Off"),
    NOISE_CANCELLING_TRANSPARENCY(0x06, "Noise cancelling ↔ Transparency"), ALL(0x07, "All three"),
}

object RedmiConfig {
    const val GESTURES = 0x02
    const val EAR_DETECTION = 0x06
    const val EQ_PRESET = 0x07
    const val LONG_GESTURES = 0x0a
    const val EFFECT_STRENGTH = 0x0b
    const val EQ_CURVE = 0x37
    const val STRENGTH_ANC = 0x01
    const val STRENGTH_TRANSPARENCY = 0x02

    /** What Gadgetbridge asks for after authentication. */
    val INITIAL = listOf(EFFECT_STRENGTH, Toggle.ADAPTIVE_ANC.config, GESTURES, LONG_GESTURES, EAR_DETECTION,
        Toggle.DOUBLE_CONNECTION.config, Toggle.AUTO_ANSWER.config, Toggle.ADAPTIVE_SOUND.config, EQ_PRESET, EQ_CURVE)

    /** Custom EQ: 10 bands, −6…+6 dB in 1 dB steps. */
    val EQ_BANDS_HZ = listOf(62, 125, 250, 500, 1000, 2016, 4000, 8000, 12000, 16000)
    /** Sign-magnitude: 0x8N is −N dB. */
    fun encodeLevel(db: Int): Byte = db.coerceIn(-6, 6).let { if (it < 0) (0x80 or -it).toByte() else it.toByte() }
    fun decodeLevel(b: Byte): Int = (b.toInt() and 0xFF).let { if (it and 0x80 != 0) -(it and 0x7F) else it }
}

data class RedmiBudsModel(
    val name: String,
    val ambientModes: List<AmbientSound> = emptyList(),
    val ancStrengths: List<AncStrength> = emptyList(),
    val transparencyStrengths: List<TransparencyStrength> = emptyList(),
    val eqPresets: List<EqPreset> = emptyList(),
    val customEq: Boolean = false,
    val toggles: List<Toggle> = emptyList(),
    val earDetection: Boolean = false,
    val findEarbuds: Boolean = false,
    /** Gesture actions per tap type, as in Gadgetbridge's coordinators; empty = not offered. */
    val singleTapActions: List<GestureAction> = emptyList(),
    val tapActions: List<GestureAction> = emptyList(),
    val longPressActions: List<LongGestureAction> = emptyList(),
    val ambientCycles: List<AmbientCycle> = emptyList(),
) {
    /** Tap types this model lets you remap, with the actions each one offers. */
    val gestures: List<Pair<TapType, List<GestureAction>>>
        get() = buildList {
            if (singleTapActions.isNotEmpty()) add(TapType.SINGLE to singleTapActions)
            if (tapActions.isNotEmpty()) {
                add(TapType.DOUBLE to tapActions)
                add(TapType.TRIPLE to tapActions)
            }
        }

    val features: List<String>
        get() = buildList {
            add("battery")
            if (ambientModes.isNotEmpty()) add("noise cancelling / transparency")
            if (eqPresets.isNotEmpty() || customEq) add("equalizer")
            if (toggles.isNotEmpty() || earDetection) add("toggles")
            if (findEarbuds) add("find earbuds")
            if (gestures.isNotEmpty() || longPressActions.isNotEmpty()) add("touch controls")
            add("firmware")
        }
}

private val TAP_ACTIONS = GestureAction.entries - GestureAction.NONE

object RedmiBudsModels {
    private val buds5Pro = RedmiBudsModel(
        "Redmi Buds 5 Pro",
        ambientModes = AmbientSound.entries,
        ancStrengths = listOf(AncStrength.BALANCED, AncStrength.LIGHT, AncStrength.DEEP),
        transparencyStrengths = TransparencyStrength.entries,
        eqPresets = listOf(EqPreset.STANDARD, EqPreset.TREBLE, EqPreset.BASS, EqPreset.VOICE, EqPreset.CUSTOM),
        customEq = true,
        toggles = listOf(Toggle.ADAPTIVE_ANC, Toggle.ADAPTIVE_SOUND, Toggle.AUTO_ANSWER, Toggle.DOUBLE_CONNECTION),
        earDetection = true,
        singleTapActions = GestureAction.entries,
        tapActions = TAP_ACTIONS,
        longPressActions = listOf(LongGestureAction.VOICE_ASSISTANT, LongGestureAction.AMBIENT_SOUND_CONTROL),
        ambientCycles = AmbientCycle.entries,
    )

    /** Same capabilities as Gadgetbridge's coordinators; names are their Bluetooth-name patterns. */
    val all: List<RedmiBudsModel> = listOf(
        RedmiBudsModel(
            "Redmi Buds 3 Pro",
            ambientModes = AmbientSound.entries,
            ancStrengths = AncStrength.entries,
            transparencyStrengths = listOf(TransparencyStrength.REGULAR, TransparencyStrength.VOICE),
            eqPresets = listOf(EqPreset.STANDARD, EqPreset.TREBLE, EqPreset.BASS, EqPreset.VOICE),
            toggles = listOf(Toggle.AUTO_ANSWER, Toggle.DOUBLE_CONNECTION),
            earDetection = true,
            tapActions = TAP_ACTIONS,
            longPressActions = listOf(LongGestureAction.VOICE_ASSISTANT, LongGestureAction.AMBIENT_SOUND_CONTROL),
            ambientCycles = AmbientCycle.entries,
        ),
        RedmiBudsModel("Redmi Buds 4 Active"),
        buds5Pro,
        buds5Pro.copy(name = "Redmi Buds 6"),
        buds5Pro.copy(name = "Redmi Buds 6 Pro"),
        RedmiBudsModel(
            "Redmi Buds 6 Active",
            eqPresets = listOf(EqPreset.STANDARD, EqPreset.TREBLE, EqPreset.BASS, EqPreset.VOICE, EqPreset.VOLUME),
            findEarbuds = true,
            singleTapActions = GestureAction.entries,
            tapActions = TAP_ACTIONS,
            longPressActions = listOf(LongGestureAction.NONE, LongGestureAction.VOICE_ASSISTANT),
        ),
        RedmiBudsModel(
            "Redmi Buds 6 Lite", ambientModes = AmbientSound.entries, customEq = true, toggles = listOf(Toggle.AUTO_ANSWER), findEarbuds = true,
            singleTapActions = GestureAction.entries,
            tapActions = GestureAction.entries,
            longPressActions = listOf(LongGestureAction.VOICE_ASSISTANT, LongGestureAction.AMBIENT_SOUND_CONTROL),
            ambientCycles = AmbientCycle.entries,
        ),
        RedmiBudsModel(
            "Redmi Buds 8 Active",
            eqPresets = listOf(EqPreset.BALANCED, EqPreset.TREBLE, EqPreset.BASS, EqPreset.VOICE, EqPreset.VOLUME, EqPreset.CUSTOM),
            customEq = true,
            toggles = listOf(Toggle.ADAPTIVE_SOUND, Toggle.DOUBLE_CONNECTION),
            findEarbuds = true,
            singleTapActions = GestureAction.entries,
            tapActions = TAP_ACTIONS,
            longPressActions = listOf(LongGestureAction.NONE, LongGestureAction.VOICE_ASSISTANT),
        ),
    )

    fun byName(name: String): RedmiBudsModel? = all.firstOrNull { it.name == name }

    /** Longest model name contained in the Bluetooth name, ignoring case ("REDMI Buds 8 Active"). */
    fun guess(bluetoothName: String?): RedmiBudsModel? {
        val n = bluetoothName?.lowercase() ?: return null
        return all.filter { n.contains(it.name.lowercase()) }.maxByOrNull { it.name.length }
    }

    fun looksLikeRedmiBuds(bluetoothName: String?): Boolean =
        bluetoothName?.contains("redmi buds", ignoreCase = true) == true || bluetoothName?.contains("xiaomi buds", ignoreCase = true) == true
}

data class BudBattery(val level: Int, val charging: Boolean)

data class RedmiBudsState(
    val connected: Boolean = false,
    val authenticated: Boolean = false,
    val left: BudBattery? = null,
    val right: BudBattery? = null,
    val case: BudBattery? = null,
    val firmware: String? = null,
    val firmware2: String? = null,
    val hardware: String? = null,
    val ambient: AmbientSound? = null,
    val ancStrength: AncStrength? = null,
    val transparencyStrength: TransparencyStrength? = null,
    val eqPreset: EqPreset? = null,
    val eqCurve: List<Int>? = null,
    val earDetection: Boolean? = null,
    val toggles: Map<Toggle, Boolean> = emptyMap(),
    /** Raw action codes per tap type and side (decode with GestureAction / LongGestureAction). */
    val gestures: Map<Pair<TapType, BudSide>, Int> = emptyMap(),
    val ambientCycle: Map<BudSide, AmbientCycle> = emptyMap(),
    val error: String? = null,
)

/** Payload parsers shared by the session and the fake. */
object RedmiParsers {
    fun gesturePayload(tap: TapType, side: BudSide, code: Int): ByteArray =
        byteArrayOf(0x05, 0x00, RedmiConfig.GESTURES.toByte(), tap.code.toByte(), 0xFF.toByte(), 0xFF.toByte())
            .also { it[if (side == BudSide.LEFT) 4 else 5] = code.toByte() }

    fun ambientCyclePayload(side: BudSide, cycle: AmbientCycle): ByteArray =
        byteArrayOf(0x04, 0x00, RedmiConfig.LONG_GESTURES.toByte(), 0xFF.toByte(), 0xFF.toByte())
            .also { it[if (side == BudSide.LEFT) 3 else 4] = cycle.code.toByte() }

    /** GESTURES reply: `[tap, left, right]` records from index 3; unknown tap types are skipped. */
    fun gestures(p: ByteArray): Map<Pair<TapType, BudSide>, Int> = buildMap {
        var i = 3
        while (i + 2 < p.size) {
            TapType.entries.firstOrNull { it.code == (p[i].toInt() and 0xFF) }?.let { tap ->
                put(tap to BudSide.LEFT, p[i + 1].toInt() and 0xFF)
                put(tap to BudSide.RIGHT, p[i + 2].toInt() and 0xFF)
            }
            i += 3
        }
    }

    /** LONG_GESTURES reply: left cycle at index 3, right at 4. */
    fun ambientCycles(p: ByteArray): Map<BudSide, AmbientCycle> = buildMap {
        listOf(BudSide.LEFT to 3, BudSide.RIGHT to 4).forEach { (side, i) ->
            AmbientCycle.entries.firstOrNull { it.code == (p.getOrNull(i)?.toInt()?.and(0xFF)) }?.let { put(side, it) }
        }
    }

    /** Bit 7 = charging, low 7 bits = percent, 0xff = absent. */
    fun battery(b: Byte): BudBattery? = (b.toInt() and 0xFF).let { if (it == 0xFF) null else BudBattery(it and 0x7F, it and 0x80 != 0) }

    fun version(a: Byte, b: Byte): String {
        val x = a.toInt() and 0xFF
        val y = b.toInt() and 0xFF
        return "${x shr 4}.${x and 0xF}.${y shr 4}.${y and 0xF}"
    }
}

/**
 * One authenticated connection to Redmi Buds: runs the challenge handshake, requests device
 * info and settings, answers the earbuds' status pushes, and exposes [state].
 */
class RedmiBudsDevice(
    val model: RedmiBudsModel,
    private val transport: BtTransport,
    private val log: CommandLog? = null,
    private val label: String = "bt",
    private val challenge: () -> ByteArray = RedmiBudsAuth::randomChallenge,
) {
    private val decoder = RedmiDecoder()
    private val writeLock = Mutex()
    private var seq = 0
    private val _state = MutableStateFlow(RedmiBudsState())
    val state: StateFlow<RedmiBudsState> = _state.asStateFlow()
    private var reader: Job? = null

    fun start(scope: CoroutineScope) {
        reader = scope.launch {
            _state.update { it.copy(connected = true, error = null) }
            launch { request(Opcode.AUTH_CHALLENGE, byteArrayOf(0x01) + challenge()) }
            try {
                while (isActive) decoder.feed(transport.read()).forEach { handle(it) }
            } catch (e: Exception) {
                _state.update { it.copy(connected = false, error = e.message ?: "Disconnected") }
            }
        }
    }

    fun close() {
        reader?.cancel()
        runCatching { transport.close() }
        _state.update { it.copy(connected = false) }
    }

    // --- settings -------------------------------------------------------------------------

    suspend fun setAmbient(mode: AmbientSound) {
        require(mode in model.ambientModes) { "${mode.label} isn't available on ${model.name}" }
        request(Opcode.ANC, byteArrayOf(0x02, 0x04, mode.code.toByte()))
        _state.update { it.copy(ambient = mode) }
    }

    suspend fun setAncStrength(s: AncStrength) {
        request(Opcode.SET_CONFIG, byteArrayOf(0x04, 0x00, RedmiConfig.EFFECT_STRENGTH.toByte(), RedmiConfig.STRENGTH_ANC.toByte(), s.code.toByte()))
        _state.update { it.copy(ancStrength = s) }
    }

    suspend fun setTransparencyStrength(s: TransparencyStrength) {
        request(Opcode.SET_CONFIG, byteArrayOf(0x04, 0x00, RedmiConfig.EFFECT_STRENGTH.toByte(), RedmiConfig.STRENGTH_TRANSPARENCY.toByte(), s.code.toByte()))
        _state.update { it.copy(transparencyStrength = s) }
    }

    suspend fun setEqPreset(p: EqPreset) {
        request(Opcode.SET_CONFIG, byteArrayOf(0x03, 0x00, RedmiConfig.EQ_PRESET.toByte(), p.code.toByte()))
        _state.update { it.copy(eqPreset = p) }
    }

    suspend fun setCustomEq(levelsDb: List<Int>) {
        require(levelsDb.size == RedmiConfig.EQ_BANDS_HZ.size) { "Custom EQ has 10 bands" }
        request(Opcode.SET_CONFIG, customEqPayload(levelsDb))
        _state.update { it.copy(eqCurve = levelsDb.map { l -> l.coerceIn(-6, 6) }) }
    }

    suspend fun setToggle(t: Toggle, on: Boolean) {
        request(Opcode.SET_CONFIG, byteArrayOf(0x03, 0x00, t.config.toByte(), if (on) 1 else 0))
        _state.update { it.copy(toggles = it.toggles + (t to on)) }
    }

    /**
     * One side of one gesture: `[0x05, 0x00, GESTURES, tap, left, right]`, 0xFF for the side
     * left alone (Gadgetbridge's encodeSetGesture). [code] is a GestureAction or, for LONG, a
     * LongGestureAction code.
     */
    suspend fun setGesture(tap: TapType, side: BudSide, code: Int) {
        val allowed = if (tap == TapType.LONG) model.longPressActions.map { it.code } else model.gestures.firstOrNull { it.first == tap }?.second?.map { it.code }.orEmpty()
        require(code in allowed) { "${tap.label} can't be set to that on ${model.name}" }
        request(Opcode.SET_CONFIG, RedmiParsers.gesturePayload(tap, side, code))
        _state.update { it.copy(gestures = it.gestures + ((tap to side) to code)) }
    }

    /** What a long press set to noise control cycles through: `[0x04, 0x00, LONG_GESTURES, left, right]`. */
    suspend fun setAmbientCycle(side: BudSide, cycle: AmbientCycle) {
        require(cycle in model.ambientCycles) { "${cycle.label} isn't available on ${model.name}" }
        request(Opcode.SET_CONFIG, RedmiParsers.ambientCyclePayload(side, cycle))
        _state.update { it.copy(ambientCycle = it.ambientCycle + (side to cycle)) }
    }

    /** The wire value is inverted: 0x00 means detection is on. */
    suspend fun setEarDetection(on: Boolean) {
        request(Opcode.ANC, byteArrayOf(0x02, 0x06, if (on) 0x00 else 0x01))
        _state.update { it.copy(earDetection = on) }
    }

    /** Plays a sound from the earbuds (1 = left, 2 = right, 3 = both). */
    suspend fun findEarbuds(start: Boolean, which: Int = 3) {
        request(Opcode.SET_CONFIG, byteArrayOf(0x04, 0x00, 0x09, 0x00, 0x03))
        if (start) request(Opcode.SET_CONFIG, byteArrayOf(0x04, 0x00, 0x09, 0x01, which.toByte()))
    }

    // --- incoming -------------------------------------------------------------------------

    internal suspend fun handle(m: RedmiMessage) {
        record("←", m)
        when (m.opcode) {
            Opcode.AUTH_CHALLENGE -> if (m.type == MessageType.RESPONSE) {
                // Gadgetbridge doesn't verify the earbuds' answer either; carry on to confirm.
                request(Opcode.AUTH_CONFIRM, byteArrayOf(0x01, 0x00))
            } else if (m.payload.size >= 17) {
                respond(m, byteArrayOf(0x01) + RedmiBudsAuth.response(m.payload.copyOfRange(1, 17)))
            }
            Opcode.AUTH_CONFIRM -> if (m.type != MessageType.RESPONSE) {
                respond(m, byteArrayOf(0x01))
                _state.update { it.copy(authenticated = true) }
                val all = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
                request(Opcode.GET_DEVICE_INFO, all)
                request(Opcode.GET_DEVICE_RUN_INFO, all)
                RedmiConfig.INITIAL.forEach { request(Opcode.GET_CONFIG, byteArrayOf(0x00, it.toByte())) }
            }
            Opcode.GET_DEVICE_INFO -> deviceInfo(m.payload)
            Opcode.GET_DEVICE_RUN_INFO -> runInfo(m.payload)
            Opcode.REPORT_STATUS -> {
                statusReport(m.payload)
                respond(m, ByteArray(0))
            }
            Opcode.NOTIFY_CONFIG -> {
                notifyConfig(m.payload)
                respond(m, ByteArray(0))
            }
            Opcode.GET_CONFIG -> config(m.payload)
            else -> Unit
        }
    }

    private fun deviceInfo(p: ByteArray) {
        var i = 0
        while (i + 1 < p.size) {
            val len = p[i].toInt() and 0xFF
            val index = p[i + 1].toInt() and 0xFF
            when (index) {
                0x01 -> if (i + 5 < p.size) _state.update {
                    it.copy(firmware = RedmiParsers.version(p[i + 2], p[i + 3]), firmware2 = RedmiParsers.version(p[i + 4], p[i + 5]))
                }
                0x03 -> if (i + 5 < p.size) _state.update {
                    it.copy(hardware = "VID 0x%02X%02X, PID 0x%02X%02X".format(p[i + 2], p[i + 3], p[i + 4], p[i + 5]))
                }
                0x07 -> if (i + 4 < p.size) batteries(p[i + 2], p[i + 3], p[i + 4])
            }
            if (len == 0) break
            i += len + 1
        }
    }

    private fun runInfo(p: ByteArray) {
        var i = 0
        while (i + 2 < p.size) {
            val len = p[i].toInt() and 0xFF
            when (p[i + 1].toInt() and 0xFF) {
                0x09 -> ambientOf(p[i + 2])?.let { a -> _state.update { it.copy(ambient = a) } }
                0x0A -> _state.update { it.copy(earDetection = p[i + 2].toInt() == 0) }
            }
            if (len == 0) break
            i += len + 1
        }
    }

    private fun statusReport(p: ByteArray) {
        var i = 0
        while (i + 1 < p.size) {
            val len = p[i].toInt() and 0xFF
            when (p[i + 1].toInt() and 0xFF) {
                0x00 -> if (i + 4 < p.size) batteries(p[i + 2], p[i + 3], p[i + 4])
                0x04 -> if (i + 2 < p.size) ambientOf(p[i + 2])?.let { a -> _state.update { it.copy(ambient = a) } }
            }
            if (len == 0) break
            i += len + 1
        }
    }

    private fun notifyConfig(p: ByteArray) {
        var i = 0
        while (i + 2 < p.size) {
            val len = p[i].toInt() and 0xFF
            if ((p[i + 2].toInt() and 0xFF) == RedmiConfig.EFFECT_STRENGTH && i + 4 < p.size) {
                val mode = ambientOf(p[i + 3])
                val strength = p[i + 4].toInt() and 0xFF
                _state.update {
                    if (mode == AmbientSound.NOISE_CANCELLING) {
                        it.copy(ambient = mode, ancStrength = AncStrength.entries.firstOrNull { s -> s.code == strength } ?: it.ancStrength)
                    } else {
                        it.copy(ambient = mode ?: it.ambient, transparencyStrength = TransparencyStrength.entries.firstOrNull { s -> s.code == strength } ?: it.transparencyStrength)
                    }
                }
            }
            if (len == 0) break
            i += len + 1
        }
    }

    private fun config(p: ByteArray) {
        if (p.size < 4) return
        val v = p[3].toInt() and 0xFF
        when (val id = p[2].toInt() and 0xFF) {
            RedmiConfig.EQ_PRESET -> _state.update { it.copy(eqPreset = EqPreset.entries.firstOrNull { e -> e.code == v }) }
            RedmiConfig.EFFECT_STRENGTH -> if (p.size >= 5) {
                val s = p[4].toInt() and 0xFF
                _state.update {
                    when (v) {
                        RedmiConfig.STRENGTH_ANC -> it.copy(ancStrength = AncStrength.entries.firstOrNull { e -> e.code == s })
                        RedmiConfig.STRENGTH_TRANSPARENCY -> it.copy(transparencyStrength = TransparencyStrength.entries.firstOrNull { e -> e.code == s })
                        else -> it
                    }
                }
            }
            RedmiConfig.GESTURES -> _state.update { it.copy(gestures = RedmiParsers.gestures(p)) }
            RedmiConfig.LONG_GESTURES -> _state.update { it.copy(ambientCycle = RedmiParsers.ambientCycles(p)) }
            RedmiConfig.EQ_CURVE -> {
                val levels = RedmiConfig.EQ_BANDS_HZ.indices.mapNotNull { b -> p.getOrNull(EQ_CURVE_FIRST_LEVEL + 3 * b)?.let(RedmiConfig::decodeLevel) }
                if (levels.size == RedmiConfig.EQ_BANDS_HZ.size) _state.update { it.copy(eqCurve = levels) }
            }
            else -> Toggle.entries.firstOrNull { it.config == id }?.let { t -> _state.update { it.copy(toggles = it.toggles + (t to (v == 1))) } }
        }
    }

    private fun batteries(left: Byte, right: Byte, case: Byte) {
        _state.update { it.copy(left = RedmiParsers.battery(left), right = RedmiParsers.battery(right), case = RedmiParsers.battery(case)) }
    }

    private fun ambientOf(b: Byte) = AmbientSound.entries.firstOrNull { it.code == (b.toInt() and 0xFF) }

    // --- outgoing -------------------------------------------------------------------------

    private suspend fun request(op: Opcode, payload: ByteArray) = send(RedmiMessage(MessageType.PHONE_REQUEST, op, nextSeq(), payload))

    private suspend fun respond(to: RedmiMessage, payload: ByteArray) = send(RedmiMessage(MessageType.RESPONSE, to.opcode, to.sequence, payload))

    private suspend fun send(m: RedmiMessage) = writeLock.withLock {
        record("→", m)
        transport.write(m.encode())
    }

    @Synchronized
    private fun nextSeq(): Int = seq.also { seq = (seq + 1) and 0xFF }

    private fun record(arrow: String, m: RedmiMessage) {
        val l = log ?: return
        l.record(
            LogEntry(
                id = l.nextId(),
                timestampMillis = System.currentTimeMillis(),
                method = "BT $arrow",
                url = "rfcomm://$label  ${m.type} ${m.opcode}",
                requestHeaders = emptyMap(),
                requestBody = if (arrow == "→") m.encode().joinToString(" ") { "%02x".format(it) } else "",
                status = null,
                responseBody = if (arrow == "←") m.payload.joinToString(" ") { "%02x".format(it) } else null,
                durationMillis = 0,
            ),
        )
    }

    companion object {
        /** Index of the first band level in an EQ_CURVE reply, then every 3 bytes. */
        const val EQ_CURVE_FIRST_LEVEL = 12

        fun customEqPayload(levelsDb: List<Int>): ByteArray {
            val bands = RedmiConfig.EQ_BANDS_HZ
            val out = ByteArray(7 + 3 * bands.size)
            byteArrayOf(0x24, 0x00, RedmiConfig.EQ_CURVE.toByte(), 0x05, 0x01, 0x01, bands.size.toByte()).copyInto(out)
            bands.forEachIndexed { i, hz ->
                out[7 + i * 3] = (hz ushr 8).toByte()
                out[8 + i * 3] = hz.toByte()
                out[9 + i * 3] = RedmiConfig.encodeLevel(levelsDb[i])
            }
            return out
        }
    }
}
