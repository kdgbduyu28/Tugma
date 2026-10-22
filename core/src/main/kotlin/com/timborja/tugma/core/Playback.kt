package com.timborja.tugma.core

import kotlinx.coroutines.flow.StateFlow

enum class TransportState { PLAYING, PAUSED, STOPPED, NO_MEDIA, TRANSITIONING, UNKNOWN }

/** What a speaker reports right now. Null fields mean the speaker didn't answer that query. */
data class PlaybackStatus(
    val volume: Int?,
    val maxVolume: Int,
    val muted: Boolean?,
    val transport: TransportState,
    val title: String? = null,
    val artist: String? = null,
)

/**
 * Volume, mute and transport for one speaker. These are everyday controls, so they don't go
 * through a [CommandPlan]; only bonding/topology writes need confirmation.
 */
interface PlaybackControl {
    val capabilities: Set<Capability>
    suspend fun status(): PlaybackStatus
    suspend fun setVolume(volume: Int)
    suspend fun setMuted(muted: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun next()
    suspend fun previous()
}

/**
 * Speakers that can play a link directly: internet radio or an audio file. [live] marks a
 * continuous stream, which some platforms (Sonos) need to know.
 */
interface UrlPlayback {
    suspend fun playUrl(url: String, title: String, live: Boolean = true)
}

/** A saved link. */
@kotlinx.serialization.Serializable
data class Station(val name: String, val url: String, val live: Boolean = true) {
    val isValidUrl: Boolean get() = Regex("^https?://[^\\s/]+.*$", RegexOption.IGNORE_CASE).matches(url.trim())
}

data class SourceState(
    val loading: Boolean = false,
    val speakers: List<SpeakerSummary> = emptyList(),
    val error: String? = null,
    val loadedOnce: Boolean = false,
)

/** One brand's speakers as the app sees them. Each adapter's repository implements this. */
interface SpeakerSource {
    val brand: Brand
    val summaries: StateFlow<SourceState>
    suspend fun refresh()
    fun playback(id: String): PlaybackControl?
}
