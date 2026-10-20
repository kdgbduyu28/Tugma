package com.timborja.tugma.eq

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun resource(name: String) = requireNotNull(EqTest::class.java.getResource("/$name")).readText()
private fun near(expected: Double, actual: Double, tol: Double, what: String = "") =
    assertTrue(abs(expected - actual) <= tol, "$what expected $expected ± $tol, got $actual")

class EqTest {
    @Test
    fun `peaking filter hits its gain at the centre and fades away`() {
        val pk = Biquad(FilterType.PEAKING, 1000.0, 6.0, 1.0)
        near(6.0, pk.magnitudeDb(1000.0), 0.01, "centre")
        near(0.0, pk.magnitudeDb(20.0), 0.1, "far below")
        near(0.0, pk.magnitudeDb(18000.0), 0.3, "far above")
    }

    @Test
    fun `shelves reach their gain on their side only`() {
        val low = Biquad(FilterType.LOW_SHELF, 100.0, 6.0, 0.707)
        near(6.0, low.magnitudeDb(20.0), 0.6, "low shelf, bass")
        near(0.0, low.magnitudeDb(5000.0), 0.1, "low shelf, treble")
        near(3.0, low.magnitudeDb(100.0), 0.1, "low shelf, half gain at fc")
        val high = Biquad(FilterType.HIGH_SHELF, 8000.0, -4.0, 0.707)
        near(-4.0, high.magnitudeDb(20000.0), 0.8, "high shelf, treble")
        near(0.0, high.magnitudeDb(100.0), 0.05, "high shelf, bass")
    }

    @Test
    fun `curve interpolation is linear in log frequency`() {
        val bands = listOf(EqBand(100.0, 0.0), EqBand(400.0, 6.0))
        near(3.0, EqMath.gainAt(bands, 200.0), 1e-9, "octave midpoint")
        assertEquals(0.0, EqMath.gainAt(bands, 20.0))
        assertEquals(6.0, EqMath.gainAt(bands, 20_000.0))
    }

    @Test
    fun `band cutoffs sit between centres`() {
        val cuts = EqMath.bandCutoffs(EqMath.GRAPHIC_10)
        assertEquals(10, cuts.size)
        near(sqrt(31.0 * 62.0), cuts[0], 1e-9)
        assertEquals(EqMath.TOP_EDGE_HZ, cuts.last())
        assertTrue(cuts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `imports AutoEQ parametric files`() {
        val r = AutoEqImport.parse(resource("sample_ParametricEQ.txt"), "autoeq:test", "Test IEM")
        val p = r.profile
        assertEquals(EqSource.AUTOEQ, p.source)
        assertEquals(-6.2, p.preampDb)
        assertEquals(31, p.bands.size)
        // 3150 Hz band sits right on the +4 dB peak (minus the -3 dB dip's tail).
        near(3.6, EqMath.gainAt(p.bands, 3150.0), 0.8, "peak region")
        // Bass shelf of +5.5 dB dominates at 20 Hz.
        near(5.5, p.bands.first().gainDb, 0.7, "bass")
        assertTrue(r.warnings.any { "LP" in it }, "unsupported LP filter is reported")
        assertTrue(p.bands.none { abs(it.gainDb - 9.0) < 0.5 }, "OFF filter is ignored")
    }

    @Test
    fun `imports AutoEQ graphic files with peak-normalising preamp`() {
        val p = AutoEqImport.parse(resource("sample_GraphicEQ.txt"), "autoeq:g", "Graphic").profile
        assertEquals(31, p.bands.size)
        near(3.0, EqMath.gainAt(p.bands, 100.0), 0.01)
        near(-3.0, p.preampDb, 0.01, "peak 3 dB → preamp -3 dB")
        assertTrue(p.headroomDb <= 0.01)
    }

    @Test
    fun `rejects files without filters`() {
        assertFailsWith<IllegalArgumentException> { AutoEqImport.parse("hello", "x", "x") }
    }

    @Test
    fun `library assignments and removal`() {
        val mine = EqProfile("user:1", "Mine", -2.0, EqMath.flat().mapIndexed { i, b -> b.copy(gainDb = i.toDouble()) })
        var lib = EqLibrary().upsert(mine).assign("bt:AA:BB", "user:1").copy(defaultProfileId = "preset:bass")
        assertEquals("Mine", lib.profileFor("bt:AA:BB").name)
        assertEquals("Bass boost", lib.profileFor("bt:CC:DD").name)
        assertEquals("Bass boost", lib.profileFor(null).name)
        lib = lib.remove("user:1")
        assertEquals("Bass boost", lib.profileFor("bt:AA:BB").name)
        assertTrue(lib.assignments.isEmpty())
        assertFailsWith<IllegalArgumentException> { lib.upsert(EqPresets.FLAT) }
    }

    @Test
    fun `gains are clamped and json round-trips`() {
        val wild = EqProfile("user:2", "Wild", 20.0, listOf(EqBand(100.0, 40.0), EqBand(1000.0, -40.0)))
        val lib = EqLibrary().upsert(wild)
        val stored = lib.profile("user:2")!!
        assertEquals(EqMath.MAX_PREAMP, stored.preampDb)
        assertEquals(listOf(EqMath.MAX_GAIN, EqMath.MIN_GAIN), stored.bands.map { it.gainDb })
        val json = Json.encodeToString(EqLibrary.serializer(), lib)
        assertEquals(lib, Json.decodeFromString(EqLibrary.serializer(), json))
        assertEquals(10.0, stored.withBand(0, 10.0).bands[0].gainDb)
    }

    @Test
    fun `device keys prefer the address`() {
        assertEquals("bt:AA:BB:CC:DD:EE:FF", EqDeviceKey.bluetooth("aa:bb:cc:dd:ee:ff", "Buds"))
        assertEquals("name:Buds", EqDeviceKey.bluetooth("", "Buds"))
        assertEquals("name:Buds", EqDeviceKey.bluetooth("00:00:00:00:00:00", "Buds"))
    }

    @Test
    fun `presets are 10-band and flat is flat`() {
        assertTrue(EqPresets.all.all { it.bands.size == 10 })
        assertTrue(EqPresets.FLAT.isFlat)
        assertTrue(EqPresets.all.drop(1).all { it.headroomDb <= 0.01 }, "presets leave headroom")
    }
}
