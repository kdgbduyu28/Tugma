package com.timborja.tugma.eq

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.RequiresApi
import com.timborja.tugma.eq.EqBand as TugmaBand

/** The EQ attached to one audio session (0 = the global output mix). */
interface EffectChain {
    val engineName: String
    fun apply(profile: EqProfile)
    fun release()

    companion object {
        /** DynamicsProcessing on Android 9+, the older fixed-band Equalizer before that. Throws if the session refuses effects. */
        fun create(session: Int, profile: EqProfile): EffectChain =
            if (Build.VERSION.SDK_INT >= 28) DynamicsChain(session, profile.bands.size) else LegacyEqualizerChain(session)

        const val PRIORITY = 1000
    }
}

@RequiresApi(28)
private class DynamicsChain(private val session: Int, initialBands: Int) : EffectChain {
    override val engineName = "DynamicsProcessing"
    private var bandCount = initialBands
    private var dp = build(bandCount)

    private fun build(bands: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true, bands, // pre-EQ: our gain curve
            false, 0, // no multiband compressor
            false, 0, // no post-EQ
            true, // limiter catches boosts that would clip
        ).build()
        return DynamicsProcessing(EffectChain.PRIORITY, session, config)
    }

    override fun apply(profile: EqProfile) {
        val bands = profile.bands.sortedBy { it.frequencyHz }
        if (bands.size != bandCount) {
            // Band count is fixed when the effect is created; rebuild for a different layout.
            dp.release()
            bandCount = bands.size
            dp = build(bandCount)
        }
        val cutoffs = EqMath.bandCutoffs(bands.map(TugmaBand::frequencyHz))
        val eq = DynamicsProcessing.Eq(true, true, bandCount)
        bands.forEachIndexed { i, b -> eq.setBand(i, DynamicsProcessing.EqBand(true, cutoffs[i].toFloat(), b.gainDb.toFloat())) }
        dp.setPreEqAllChannelsTo(eq)
        dp.setInputGainAllChannelsTo(profile.preampDb.toFloat())
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp.enabled = !profile.isFlat
    }

    override fun release() = dp.release()
}

/** Android 8.x: the device's own Equalizer bands (usually 5), sampled from the profile curve. */
private class LegacyEqualizerChain(session: Int) : EffectChain {
    override val engineName = "Equalizer"
    private val eq = Equalizer(EffectChain.PRIORITY, session)

    override fun apply(profile: EqProfile) {
        val range = eq.bandLevelRange // millibels
        for (band in 0 until eq.numberOfBands) {
            val centreHz = eq.getCenterFreq(band.toShort()) / 1000.0
            val db = EqMath.gainAt(profile.bands, centreHz) + profile.preampDb
            eq.setBandLevel(band.toShort(), (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        }
        eq.enabled = !profile.isFlat
    }

    override fun release() = eq.release()
}

/** Session broadcasts music apps send so effect apps can attach to their audio. */
object EffectSessions {
    const val OPEN = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
    const val CLOSE = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
    const val EXTRA_SESSION = AudioEffect.EXTRA_AUDIO_SESSION
    const val EXTRA_PACKAGE = AudioEffect.EXTRA_PACKAGE_NAME
}
