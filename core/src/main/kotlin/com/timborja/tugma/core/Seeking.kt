package com.timborja.tugma.core

/** Where a speaker is in the current track. [durationSeconds] is null for streams, which have no length. */
data class MediaPosition(val elapsedSeconds: Int?, val durationSeconds: Int?) {
    val seekable: Boolean get() = durationSeconds != null && durationSeconds > 0
}

/** Speakers that report a track position and can jump within the track. */
interface SeekablePlayback {
    /** Null when the speaker didn't answer. */
    suspend fun position(): MediaPosition?
    suspend fun seek(seconds: Int)
}

/** The "H:MM:SS" times UPnP uses (RelTime, TrackDuration, Seek REL_TIME). */
object MediaTime {
    /** Null for "NOT_IMPLEMENTED" and other non-times; fractions of a second are dropped. */
    fun parse(value: String?): Int? {
        val parts = value?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
        val (h, m, s) = parts.map { it.substringBefore('.').toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        return h * 3600 + m * 60 + s
    }

    fun format(seconds: Int): String = seconds.coerceAtLeast(0).let { "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) }

    /** "3:35", or "1:02:05" past an hour, for display. */
    fun clock(seconds: Int): String = seconds.coerceAtLeast(0).let {
        if (it >= 3600) "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) else "%d:%02d".format(it / 60, it % 60)
    }
}
