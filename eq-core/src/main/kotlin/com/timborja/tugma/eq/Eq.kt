package com.timborja.tugma.eq

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class EqBand(val frequencyHz: Double, val gainDb: Double)

@Serializable
enum class EqSource { PRESET, USER, AUTOEQ }

/** A gain curve: one gain per band centre, plus a preamp applied before the bands. */
@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val preampDb: Double,
    val bands: List<EqBand>,
    val source: EqSource = EqSource.USER,
    /** Where an imported profile came from, or import warnings. */
    val note: String? = null,
) {
    val isFlat: Boolean get() = abs(preampDb) < 0.05 && bands.all { abs(it.gainDb) < 0.05 }

    /** Peak boost after preamp; above 0 dB risks clipping (the engine's limiter catches it). */
    val headroomDb: Double get() = (bands.maxOfOrNull { it.gainDb } ?: 0.0) + preampDb

    fun withBand(index: Int, gainDb: Double): EqProfile =
        copy(bands = bands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = gainDb.coerceIn(EqMath.MIN_GAIN, EqMath.MAX_GAIN)) else b })

    fun clamped(): EqProfile = copy(
        preampDb = preampDb.coerceIn(EqMath.MIN_GAIN, EqMath.MAX_PREAMP),
        bands = bands.map { it.copy(gainDb = it.gainDb.coerceIn(EqMath.MIN_GAIN, EqMath.MAX_GAIN)) },
    )
}

/** Built-in profiles, all on the 10-band graphic layout. */
object EqPresets {
    private fun preset(id: String, name: String, preamp: Double, vararg gains: Double) = EqProfile(
        id = "preset:$id",
        name = name,
        preampDb = preamp,
        bands = EqMath.GRAPHIC_10.zip(gains.toList()) { f, g -> EqBand(f, g) },
        source = EqSource.PRESET,
    )

    val FLAT = preset("flat", "Flat", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val all: List<EqProfile> = listOf(
        FLAT,
        preset("bass", "Bass boost", -6.0, 6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        preset("bass-cut", "Bass reducer", 0.0, -6.0, -5.0, -4.0, -2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        preset("treble", "Treble boost", -5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0),
        preset("vocal", "Vocal", -4.0, -2.0, -2.0, -1.0, 1.0, 3.0, 4.0, 4.0, 2.0, 0.0, -1.0),
        preset("loudness", "Loudness", -5.0, 5.0, 4.0, 2.0, 0.0, -1.0, -1.0, 0.0, 2.0, 4.0, 5.0),
        preset("podcast", "Podcast", -3.0, -6.0, -4.0, -2.0, 0.0, 2.0, 3.0, 3.0, 1.0, -1.0, -3.0),
    )

    fun byId(id: String): EqProfile? = all.firstOrNull { it.id == id }
}

/** Saved user/AutoEQ profiles plus which profile each output device uses. */
@Serializable
data class EqLibrary(
    val profiles: List<EqProfile> = emptyList(),
    /** Device key (see [EqDeviceKey]) → profile id. */
    val assignments: Map<String, String> = emptyMap(),
    /** Used for devices without their own assignment. */
    val defaultProfileId: String = EqPresets.FLAT.id,
) {
    val allProfiles: List<EqProfile> get() = EqPresets.all + profiles

    fun profile(id: String): EqProfile? = EqPresets.byId(id) ?: profiles.firstOrNull { it.id == id }

    fun profileFor(deviceKey: String?): EqProfile =
        deviceKey?.let { assignments[it] }?.let(::profile) ?: profile(defaultProfileId) ?: EqPresets.FLAT

    fun upsert(p: EqProfile): EqLibrary {
        require(p.source != EqSource.PRESET) { "Presets are read-only; save a copy instead" }
        val clamped = p.clamped()
        return copy(profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) clamped else it } else profiles + clamped)
    }

    /** Removing a profile sends its devices back to the default. */
    fun remove(id: String): EqLibrary = copy(
        profiles = profiles.filter { it.id != id },
        assignments = assignments.filterValues { it != id },
        defaultProfileId = if (defaultProfileId == id) EqPresets.FLAT.id else defaultProfileId,
    )

    fun assign(deviceKey: String, profileId: String?): EqLibrary =
        copy(assignments = if (profileId == null) assignments - deviceKey else assignments + (deviceKey to profileId))
}

/** Stable keys for output devices, so a profile follows a headphone between sessions. */
object EqDeviceKey {
    const val BUILT_IN = "builtin"
    fun bluetooth(address: String?, name: String?): String =
        address?.takeIf { it.isNotBlank() && !it.startsWith("00:00:00") }?.let { "bt:${it.uppercase()}" } ?: "name:${name.orEmpty()}"
}

object EqMath {
    const val MIN_GAIN = -15.0
    const val MAX_GAIN = 15.0
    const val MAX_PREAMP = 6.0
    const val SAMPLE_RATE = 48_000.0
    const val TOP_EDGE_HZ = 20_000.0

    val GRAPHIC_10 = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    /** ISO third-octave centres, used for imported (AutoEQ) curves. */
    val THIRD_OCTAVE_31 = listOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
    )

    fun flat(centres: List<Double> = GRAPHIC_10) = centres.map { EqBand(it, 0.0) }

    /** Gain of the curve at [frequencyHz]: straight lines between band points on a log-frequency axis. */
    fun gainAt(bands: List<EqBand>, frequencyHz: Double): Double {
        if (bands.isEmpty()) return 0.0
        val sorted = bands.sortedBy { it.frequencyHz }
        if (frequencyHz <= sorted.first().frequencyHz) return sorted.first().gainDb
        if (frequencyHz >= sorted.last().frequencyHz) return sorted.last().gainDb
        val hi = sorted.indexOfFirst { it.frequencyHz >= frequencyHz }
        val a = sorted[hi - 1]
        val b = sorted[hi]
        val t = (ln(frequencyHz) - ln(a.frequencyHz)) / (ln(b.frequencyHz) - ln(a.frequencyHz))
        return a.gainDb + t * (b.gainDb - a.gainDb)
    }

    /** Resamples any curve onto new band centres (e.g. an imported curve onto a 5-band device EQ). */
    fun resample(bands: List<EqBand>, centres: List<Double>): List<EqBand> = centres.map { EqBand(it, gainAt(bands, it)) }

    /**
     * Upper cutoff of each band for Android's DynamicsProcessing, whose EQ bands are contiguous
     * regions ending at a cutoff: the geometric midpoint between neighbouring centres.
     */
    fun bandCutoffs(centres: List<Double>): List<Double> =
        centres.indices.map { i -> if (i == centres.lastIndex) TOP_EDGE_HZ else sqrt(centres[i] * centres[i + 1]) }

    /** Log-spaced frequencies for drawing a response curve. */
    fun plotFrequencies(points: Int = 96, from: Double = 20.0, to: Double = 20_000.0): List<Double> =
        (0 until points).map { i -> from * (to / from).pow(i / (points - 1.0)) }
}

enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF }

/** One RBJ-cookbook biquad, used to turn parametric EQ files into a gain curve. */
data class Biquad(val type: FilterType, val frequencyHz: Double, val gainDb: Double, val q: Double) {
    /** Magnitude response in dB at [f] for sample rate [fs]. */
    fun magnitudeDb(f: Double, fs: Double = EqMath.SAMPLE_RATE): Double {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * frequencyHz / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * max(q, 1e-3))
        val sa = 2 * sqrt(a) * alpha
        val (b0, b1, b2, a0, a1, a2) = when (type) {
            FilterType.PEAKING -> Coeffs(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
            FilterType.LOW_SHELF -> Coeffs(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
            )
            FilterType.HIGH_SHELF -> Coeffs(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
            )
        }
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = a0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    private data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double)
}

data class ImportResult(val profile: EqProfile, val warnings: List<String>)

/**
 * Reads AutoEQ (github.com/jaakkopasanen/AutoEq) result files:
 * `ParametricEQ.txt` (Preamp + PK/LSC/HSC filters) and `GraphicEQ.txt` (frequency/gain pairs).
 */
object AutoEqImport {
    private val preampRe = Regex("""^\s*Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """^\s*Filter\s*\d*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+(-?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val graphicRe = Regex("""^\s*GraphicEQ:\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, id: String, name: String): ImportResult {
        graphicRe.find(text.lines().firstOrNull { graphicRe.containsMatchIn(it) }.orEmpty())?.let { return parseGraphic(it.groupValues[1], id, name) }
        return parseParametric(text, id, name)
    }

    fun parseParametric(text: String, id: String, name: String): ImportResult {
        val warnings = mutableListOf<String>()
        var preamp = 0.0
        val filters = mutableListOf<Biquad>()
        for (line in text.lines()) {
            preampRe.find(line)?.let { preamp = it.groupValues[1].toDouble() }
            val m = filterRe.find(line) ?: continue
            if (m.groupValues[1].equals("OFF", true)) continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> FilterType.PEAKING
                "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
                "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
                else -> { warnings += "Skipped unsupported filter type ${m.groupValues[2]} at ${m.groupValues[3]} Hz"; null }
            } ?: continue
            val gain = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val q = m.groupValues[5].toDoubleOrNull() ?: 0.707
            filters += Biquad(type, m.groupValues[3].toDouble(), gain, q)
        }
        require(filters.isNotEmpty()) { "No filters found. Expected an AutoEQ ParametricEQ.txt or GraphicEQ.txt file." }
        val bands = EqMath.THIRD_OCTAVE_31.map { f -> EqBand(f, filters.sumOf { it.magnitudeDb(f) }) }
        return finish(id, name, preamp, bands, "Imported ${filters.size} parametric filters", warnings)
    }

    fun parseGraphic(pairs: String, id: String, name: String): ImportResult {
        val points = pairs.split(';').mapNotNull { p ->
            val parts = p.trim().split(Regex("\\s+"))
            val f = parts.getOrNull(0)?.toDoubleOrNull()
            val g = parts.getOrNull(1)?.toDoubleOrNull()
            if (f != null && g != null && f > 0) EqBand(f, g) else null
        }
        require(points.size >= 2) { "GraphicEQ line has no frequency/gain pairs" }
        // GraphicEQ curves carry their own level; move the peak to 0 dB as a preamp, as AutoEQ does.
        val bands = EqMath.resample(points, EqMath.THIRD_OCTAVE_31)
        val peak = bands.maxOf { it.gainDb }
        return finish(id, name, -max(0.0, peak), bands, "Imported ${points.size}-point GraphicEQ", emptyList())
    }

    private fun finish(id: String, name: String, preamp: Double, bands: List<EqBand>, note: String, warnings: List<String>): ImportResult {
        val w = warnings.toMutableList()
        if (bands.any { abs(it.gainDb) > EqMath.MAX_GAIN }) w += "Some bands exceed ±${EqMath.MAX_GAIN.toInt()} dB and were limited"
        val profile = EqProfile(id, name, preamp, bands, EqSource.AUTOEQ, (listOf(note) + w).joinToString(". ")).clamped()
        return ImportResult(profile, w)
    }
}
