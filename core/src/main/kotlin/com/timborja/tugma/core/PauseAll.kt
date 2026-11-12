package com.timborja.tugma.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A speaker that "Pause all" paused, so Resume restarts exactly those and nothing else. */
data class PausedSpeaker(val brand: Brand, val id: String)

/**
 * "Pause all" for brands without a group model of their own (DLNA, Linkplay, Cast); Sonos pauses
 * per group instead. Speakers are asked in parallel; ones that don't answer or aren't playing
 * are left alone.
 */
object PauseAll {
    suspend fun pause(targets: List<Pair<PausedSpeaker, PlaybackControl>>): List<PausedSpeaker> = coroutineScope {
        targets.map { (who, control) ->
            async {
                val playing = runCatching { control.status().transport == TransportState.PLAYING }.getOrDefault(false)
                who.takeIf { playing && runCatching { control.pause() }.isSuccess }
            }
        }.awaitAll().filterNotNull().distinct()
    }

    /** Plays each paused speaker again; returns how many accepted. Speakers that vanished count as failures. */
    suspend fun resume(paused: List<PausedSpeaker>, lookup: (PausedSpeaker) -> PlaybackControl?): Int = coroutineScope {
        paused.map { who -> async { lookup(who)?.let { c -> runCatching { c.play() }.isSuccess } ?: false } }.awaitAll().count { it }
    }

    /** Every visible speaker of [sources] that can pause, as targets for [pause]. */
    fun targets(sources: List<SpeakerSource>): List<Pair<PausedSpeaker, PlaybackControl>> = sources.flatMap { source ->
        source.summaries.value.speakers
            .filter { !it.hidden && Capability.TRANSPORT in it.capabilities }
            .mapNotNull { s -> source.playback(s.id)?.let { PausedSpeaker(s.brand, s.id) to it } }
    }
}
