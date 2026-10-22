package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.SoapFault

/**
 * One sound setting. EQ types and ranges follow SoCo `core.py`; the speaker answers with a fault
 * for settings it doesn't have (a Sub level on a bar without a Sub, height on a Beam Gen 1…),
 * so whatever reads back is what the UI shows.
 */
enum class SoundSetting(val label: String, val range: IntRange?, val eqType: String?, val homeTheaterOnly: Boolean = false) {
    BASS("Bass", -10..10, null),
    TREBLE("Treble", -10..10, null),
    LOUDNESS("Loudness", null, null),
    NIGHT_MODE("Night sound", null, "NightMode", homeTheaterOnly = true),
    DIALOG("Speech enhancement", null, "DialogLevel", homeTheaterOnly = true),
    SUB_ENABLED("Sub", null, "SubEnable", homeTheaterOnly = true),
    SUB_GAIN("Sub level", -15..15, "SubGain", homeTheaterOnly = true),
    SURROUND_ENABLED("Surrounds", null, "SurroundEnable", homeTheaterOnly = true),
    SURROUND_LEVEL("Surround level (TV)", -15..15, "SurroundLevel", homeTheaterOnly = true),
    MUSIC_SURROUND_LEVEL("Surround level (music)", -15..15, "MusicSurroundLevel", homeTheaterOnly = true),
    HEIGHT_LEVEL("Height level", -10..10, "HeightChannelLevel", homeTheaterOnly = true),
    /** Surrounds at full volume for music instead of ambient (SoCo `surround_full_volume_enabled`). */
    SURROUND_FULL_VOLUME("Full-volume surrounds for music", null, "SurroundMode", homeTheaterOnly = true),
    /** Lip-sync delay for the TV input (SoCo `audio_delay`). */
    AUDIO_DELAY("Lip sync delay", 0..5, "AudioDelay", homeTheaterOnly = true),
    ;

    val isSwitch: Boolean get() = range == null
}

/** What a speaker reported. Settings it doesn't support are absent. */
data class SoundState(val values: Map<SoundSetting, Int>) {
    operator fun get(s: SoundSetting): Int? = values[s]
    fun on(s: SoundSetting): Boolean? = values[s]?.let { it != 0 }
    val supported: List<SoundSetting> get() = SoundSetting.entries.filter { it in values }
}

/**
 * Sound settings on one speaker's RenderingControl. For a stereo pair or home theater, use the
 * visible (left/soundbar) player: it applies them to the whole bond.
 */
class SonosSound(private val soap: SoapClient, private val speaker: Endpoint) {
    private fun call(action: String, vararg args: Pair<String, String>) =
        SoapCall(speaker.baseUrl, SonosClient.RC_CONTROL, SonosClient.RC_SERVICE, action, listOf("InstanceID" to "0") + args)

    /** Reads every setting; [homeTheater] = false skips the soundbar-only ones to save requests. */
    suspend fun read(homeTheater: Boolean): SoundState {
        val out = linkedMapOf<SoundSetting, Int>()
        for (s in SoundSetting.entries) {
            if (s.homeTheaterOnly && !homeTheater) continue
            get(s)?.let { out[s] = it }
        }
        return SoundState(out)
    }

    /** Null when the speaker doesn't have this setting. */
    suspend fun get(s: SoundSetting): Int? = try {
        val out = when (s) {
            SoundSetting.BASS -> soap.call(call("GetBass"))["CurrentBass"]
            SoundSetting.TREBLE -> soap.call(call("GetTreble"))["CurrentTreble"]
            SoundSetting.LOUDNESS -> soap.call(call("GetLoudness", "Channel" to "Master"))["CurrentLoudness"]
            else -> soap.call(call("GetEQ", "EQType" to s.eqType!!))["CurrentValue"]
        }
        out?.trim()?.toIntOrNull()
    } catch (_: SoapFault) {
        null
    }

    suspend fun set(s: SoundSetting, value: Int) {
        val v = s.range?.let { value.coerceIn(it) } ?: if (value != 0) 1 else 0
        when (s) {
            SoundSetting.BASS -> soap.call(call("SetBass", "DesiredBass" to v.toString()))
            SoundSetting.TREBLE -> soap.call(call("SetTreble", "DesiredTreble" to v.toString()))
            SoundSetting.LOUDNESS -> soap.call(call("SetLoudness", "Channel" to "Master", "DesiredLoudness" to v.toString()))
            else -> soap.call(call("SetEQ", "EQType" to s.eqType!!, "DesiredValue" to v.toString()))
        }
    }

    /** Back to Sonos defaults: flat bass/treble, loudness on, levels at 0. Switches other than loudness are left alone. */
    suspend fun reset(state: SoundState) {
        for (s in state.supported) {
            when {
                s == SoundSetting.LOUDNESS -> set(s, 1)
                !s.isSwitch -> if (state[s] != 0) set(s, 0)
            }
        }
    }
}
