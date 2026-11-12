package com.timborja.tugma.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * "Mute all" in the volume mixer: mutes every speaker that isn't muted already and remembers
 * which, so "Unmute" leaves alone the ones the user had muted on purpose.
 */
object MuteAll {
    /** Returns the keys it muted. [alreadyMuted] speakers are skipped. */
    suspend fun <K> mute(targets: Map<K, PlaybackControl>, alreadyMuted: (K) -> Boolean): List<K> = coroutineScope {
        targets.filterKeys { !alreadyMuted(it) }.map { (key, control) ->
            async { key.takeIf { runCatching { control.setMuted(true) }.isSuccess } }
        }.awaitAll().filterNotNull()
    }

    /** Unmutes [keys]; returns the ones that accepted. Speakers that vanished are skipped. */
    suspend fun <K> unmute(keys: List<K>, lookup: (K) -> PlaybackControl?): List<K> = coroutineScope {
        keys.map { key -> async { key.takeIf { lookup(key)?.let { c -> runCatching { c.setMuted(false) }.isSuccess } == true } } }
            .awaitAll().filterNotNull()
    }
}
