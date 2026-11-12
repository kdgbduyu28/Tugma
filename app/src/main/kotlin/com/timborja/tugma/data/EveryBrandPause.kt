package com.timborja.tugma.data

import com.timborja.tugma.core.PauseAll
import com.timborja.tugma.core.PausedSpeaker
import com.timborja.tugma.core.SpeakerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** "Pause all" and "Resume" across brands: Sonos by group, everything else speaker by speaker. */
class EveryBrandPause(private val sonos: SonosRepository, private val others: List<SpeakerSource>) {
    private val paused = MutableStateFlow<List<PausedSpeaker>>(emptyList())

    /** True while Resume has something to restart. */
    val canResume: Flow<Boolean> = combine(sonos.paused, paused) { groups, speakers -> groups.isNotEmpty() || speakers.isNotEmpty() }

    /** Returns how many Sonos groups and other speakers were paused. */
    suspend fun pauseAll(): Int {
        val groups = runCatching { sonos.pauseAll() }.getOrDefault(0)
        paused.value = PauseAll.pause(PauseAll.targets(others))
        return groups + paused.value.size
    }

    suspend fun resume(): Int {
        val groups = runCatching { sonos.resumePaused() }.getOrDefault(0)
        val speakers = PauseAll.resume(paused.value) { p -> others.firstOrNull { it.brand == p.brand }?.playback(p.id) }
        paused.value = emptyList()
        return groups + speakers
    }
}
