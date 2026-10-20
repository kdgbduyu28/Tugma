/*
 * Model list, feature mapping and equalizer presets ported from OpenSCQ30
 * (https://github.com/Oppzippy/OpenSCQ30, GPL-3.0): README device table,
 * the per-model files under lib/src/devices/soundcore and common/modules/equalizer.rs presets.
 */
package com.timborja.tugma.bt.soundcore

enum class BatteryKind { NONE, SINGLE, DUAL }

/** Equalizer layouts Tugma can write: the shared 8-band format, one channel or both earbuds. */
enum class EqKind(val channels: Int) { NONE(0), SINGLE(1), TWS(2) }

/**
 * What Tugma has ported for a model. Features a model only implements in its own OpenSCQ30
 * module (newer ANC v2, HearID-based EQ, button remapping…) aren't ported yet.
 */
data class SoundcoreModel(
    val id: String,
    val name: String,
    val battery: BatteryKind = BatteryKind.NONE,
    /** The classic 4-byte sound-mode format (0x06 0x81). */
    val soundModesV1: Boolean = false,
    val eq: EqKind = EqKind.NONE,
    /** Full state packet parser available (only the Life Q30 so far). */
    val fullState: Boolean = false,
    val checksum: Boolean = true,
) {
    val ported: List<String>
        get() = buildList {
            if (battery != BatteryKind.NONE) add("battery")
            if (soundModesV1) add("noise cancelling / transparency")
            if (eq != EqKind.NONE) add("equalizer")
            add("firmware and serial")
        }
}

object SoundcoreModels {
    private val S = BatteryKind.SINGLE
    private val D = BatteryKind.DUAL

    val all: List<SoundcoreModel> = listOf(
        SoundcoreModel("A3004", "Soundcore Q20i", S, soundModesV1 = true),
        SoundcoreModel("A3005", "Soundcore Q11i"),
        SoundcoreModel("A3027", "Soundcore Life Q35", S, soundModesV1 = true, eq = EqKind.SINGLE),
        SoundcoreModel("A3028", "Soundcore Life Q30", S, soundModesV1 = true, eq = EqKind.SINGLE, fullState = true),
        SoundcoreModel("A3029", "Soundcore Life Tune"),
        SoundcoreModel("A3030", "Soundcore Life Tune Pro"),
        SoundcoreModel("A3031", "Soundcore Vortex", D, soundModesV1 = true, eq = EqKind.TWS),
        SoundcoreModel("A3033", "Soundcore Life 2 Neo", S, eq = EqKind.SINGLE),
        SoundcoreModel("A3035", "Soundcore Space One"),
        SoundcoreModel("A3040", "Soundcore Space Q45"),
        SoundcoreModel("A3062", "Soundcore Space One Pro"),
        SoundcoreModel("A3116", "Soundcore Motion+", S, checksum = false),
        SoundcoreModel("A3330", "Soundcore C30i"),
        SoundcoreModel("A3388", "Soundcore AeroClip"),
        SoundcoreModel("A3876", "Soundcore V20i"),
        SoundcoreModel("A3909", "Soundcore Liberty 2 Pro", D),
        SoundcoreModel("A3926", "Soundcore Life Dot 2S", D, soundModesV1 = true),
        SoundcoreModel("A3930", "Soundcore Liberty 2 Pro+", D, soundModesV1 = true),
        SoundcoreModel("A3931", "Soundcore Life Dot 2 NC", D, soundModesV1 = true),
        SoundcoreModel("A3933", "Soundcore Life Note 3", D, soundModesV1 = true, eq = EqKind.TWS),
        SoundcoreModel("A3935", "Soundcore Life A2 NC"),
        SoundcoreModel("A3936", "Soundcore Space A40", D),
        SoundcoreModel("A3939", "Soundcore Life P3"),
        SoundcoreModel("A3944", "Soundcore Life P2 Mini", D),
        SoundcoreModel("A3945", "Soundcore Life Note 3S", D, eq = EqKind.TWS),
        SoundcoreModel("A3947", "Soundcore Liberty 4 NC", D),
        SoundcoreModel("A3948", "Soundcore A20i", D),
        SoundcoreModel("A3949", "Soundcore P20i / P25i / R50i", D),
        SoundcoreModel("A3951", "Soundcore Liberty Air 2 Pro", D, soundModesV1 = true),
        SoundcoreModel("A3952", "Soundcore Liberty 3 Pro", D),
        SoundcoreModel("A3953", "Soundcore Liberty 4", D),
        SoundcoreModel("A3954", "Soundcore Liberty 4 Pro", D),
        SoundcoreModel("A3955", "Soundcore P40i", D),
        SoundcoreModel("A3957", "Soundcore Liberty 5"),
        SoundcoreModel("A3959", "Soundcore P30i / R50i NC", D),
        SoundcoreModel("A3968", "Soundcore Sport X20", D),
        SoundcoreModel("D1101", "Soundcore C50i", eq = EqKind.SINGLE),
        SoundcoreModel("D1202", "Soundcore P31i"),
        SoundcoreModel("D1202C", "Soundcore R60i NC"),
        SoundcoreModel("D1301", "Soundcore Sleep A30"),
    )

    fun byId(id: String): SoundcoreModel? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * Best guess from what Android knows: the vendor UUID's model digits, then the
     * Bluetooth name (longest matching model name wins, so "Life Q30" beats "Q30"-like names).
     */
    fun guess(bluetoothName: String?, uuidHint: String?): SoundcoreModel? {
        uuidHint?.let { hint -> all.firstOrNull { it.id.endsWith(hint) }?.let { return it } }
        val name = bluetoothName?.lowercase() ?: return null
        return all
            .flatMap { m -> m.name.removePrefix("Soundcore ").split(" / ").map { alias -> alias.lowercase() to m } }
            .filter { (alias, _) -> name.contains(alias) }
            .maxByOrNull { (alias, _) -> alias.length }
            ?.second
    }

    fun looksLikeSoundcore(bluetoothName: String?): Boolean =
        bluetoothName?.contains("soundcore", ignoreCase = true) == true || guess(bluetoothName, null) != null
}

/** Soundcore's 8 EQ bands. */
object SoundcoreEq {
    val BANDS_HZ = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)
    const val CUSTOM_PRESET_ID = 0xfefe
    /** Gains are tenths of a dB, sent as (value + 120): −12.0 … +13.4 dB. */
    const val MIN_TENTHS = -120
    const val MAX_TENTHS = 134

    data class Preset(val id: Int, val name: String, val tenths: List<Int>)

    val PRESETS: List<Preset> = listOf(
        Preset(0x0000, "Soundcore Signature", listOf(0, 0, 0, 0, 0, 0, 0, 0)),
        Preset(0x0001, "Acoustic", listOf(40, 10, 20, 20, 40, 40, 40, 20)),
        Preset(0x0002, "Bass Booster", listOf(40, 30, 10, 0, 0, 0, 0, 0)),
        Preset(0x0003, "Bass Reducer", listOf(-40, -30, -10, 0, 0, 0, 0, 0)),
        Preset(0x0004, "Classical", listOf(30, 30, -20, -20, 0, 20, 30, 40)),
        Preset(0x0005, "Podcast", listOf(-30, 20, 40, 40, 30, 20, 0, -20)),
        Preset(0x0008, "Electronic", listOf(30, 20, -20, 20, 10, 20, 30, 30)),
        Preset(0x0009, "Flat", listOf(-20, -20, -10, 0, 0, 0, -20, -20)),
        Preset(0x000b, "Jazz", listOf(20, 20, -20, -20, 0, 20, 30, 40)),
        Preset(0x000c, "Latin", listOf(0, 0, -20, -20, -20, 0, 30, 50)),
        Preset(0x000d, "Lounge", listOf(-10, 20, 40, 30, 0, -20, 20, 10)),
        Preset(0x000e, "Piano", listOf(0, 30, 30, 20, 40, 50, 30, 40)),
        Preset(0x0010, "R&B", listOf(60, 20, -20, -20, 20, 30, 30, 40)),
    )

    fun preset(id: Int): Preset? = PRESETS.firstOrNull { it.id == id }

    fun encodeGain(tenths: Int): Byte = (tenths.coerceIn(MIN_TENTHS, MAX_TENTHS) - MIN_TENTHS).toByte()
    fun decodeGain(byte: Byte): Int = (byte.toInt() and 0xFF) + MIN_TENTHS
}
