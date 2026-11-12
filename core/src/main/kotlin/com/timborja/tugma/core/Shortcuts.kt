package com.timborja.tugma.core

/** One-tap controls outside the app: the home-screen widget and the Quick Settings tile. */
enum class ShortcutAction { PLAY_PAUSE, NEXT, VOLUME_UP, VOLUME_DOWN }

object Shortcuts {
    /** 5 on a 0–100 scale; 1 on speakers with a short scale (some DLNA renderers stop at 30 or less). */
    fun volumeStep(maxVolume: Int): Int = if (maxVolume >= 50) 5 else 1

    /**
     * Runs [action] against what the speaker reported just before ([before]), so play/pause
     * toggles the right way and volume steps from the real level. Volume is left alone when the
     * speaker didn't report one.
     */
    suspend fun perform(control: PlaybackControl, action: ShortcutAction, before: PlaybackStatus?) {
        when (action) {
            ShortcutAction.PLAY_PAUSE -> if (before?.transport == TransportState.PLAYING) control.pause() else control.play()
            ShortcutAction.NEXT -> control.next()
            ShortcutAction.VOLUME_UP, ShortcutAction.VOLUME_DOWN -> before?.volume?.let { v ->
                val step = volumeStep(before.maxVolume).let { if (action == ShortcutAction.VOLUME_UP) it else -it }
                control.setVolume((v + step).coerceIn(0, before.maxVolume))
            }
        }
    }

    /** The second line: what's playing, otherwise the state and volume. Null status = unreachable. */
    fun summary(status: PlaybackStatus?, speakerName: String): String {
        if (status == null) return "Can't reach $speakerName"
        val volume = when {
            status.muted == true -> "Muted"
            status.volume != null -> "Volume ${status.volume}"
            else -> null
        }
        val track = listOfNotNull(status.title, status.artist).joinToString(" · ").ifEmpty { null }
        val state = when (status.transport) {
            TransportState.PLAYING -> null
            TransportState.PAUSED -> "Paused"
            TransportState.STOPPED -> "Stopped"
            TransportState.NO_MEDIA -> "Nothing playing"
            TransportState.TRANSITIONING -> "Loading…"
            TransportState.UNKNOWN -> null
        }
        return when {
            track != null && state != null -> "$state · $track"
            track != null -> track
            else -> listOfNotNull(state, volume).joinToString(" · ")
        }
    }

    /** Nav route of a speaker's screen, also used by the widget and tile to open it. */
    fun speakerRoute(brand: Brand, id: String): String = "speaker/${brand.name}/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    /** Only speaker screens can be opened from outside; anything else is ignored. */
    fun isSpeakerRoute(route: String?): Boolean =
        route != null && Regex("""^speaker/(${Brand.entries.joinToString("|") { it.name }})/[^/]+$""").matches(route)
}
