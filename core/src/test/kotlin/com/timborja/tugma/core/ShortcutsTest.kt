package com.timborja.tugma.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortcutsTest {
    private class Recorder : PlaybackControl {
        val calls = mutableListOf<String>()
        override val capabilities: Set<Capability> = emptySet()
        override suspend fun status() = PlaybackStatus(null, 100, null, TransportState.UNKNOWN)
        override suspend fun setVolume(volume: Int) { calls += "volume $volume" }
        override suspend fun setMuted(muted: Boolean) { calls += "mute $muted" }
        override suspend fun play() { calls += "play" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun stop() { calls += "stop" }
        override suspend fun next() { calls += "next" }
        override suspend fun previous() { calls += "previous" }
    }

    private fun status(transport: TransportState, volume: Int? = 20, max: Int = 100, muted: Boolean? = false, title: String? = null, artist: String? = null) =
        PlaybackStatus(volume, max, muted, transport, title, artist)

    @Test
    fun `play pause toggles from the reported state`() = runBlocking {
        val r = Recorder()
        Shortcuts.perform(r, ShortcutAction.PLAY_PAUSE, status(TransportState.PLAYING))
        Shortcuts.perform(r, ShortcutAction.PLAY_PAUSE, status(TransportState.PAUSED))
        Shortcuts.perform(r, ShortcutAction.PLAY_PAUSE, null)
        Shortcuts.perform(r, ShortcutAction.NEXT, null)
        assertEquals(listOf("pause", "play", "play", "next"), r.calls)
    }

    @Test
    fun `volume steps scale with the speaker and stay in range`() = runBlocking {
        val r = Recorder()
        Shortcuts.perform(r, ShortcutAction.VOLUME_UP, status(TransportState.PLAYING, volume = 98))
        Shortcuts.perform(r, ShortcutAction.VOLUME_DOWN, status(TransportState.PLAYING, volume = 3))
        Shortcuts.perform(r, ShortcutAction.VOLUME_UP, status(TransportState.PLAYING, volume = 10, max = 30))
        Shortcuts.perform(r, ShortcutAction.VOLUME_UP, status(TransportState.PLAYING, volume = null))
        assertEquals(listOf("volume 100", "volume 0", "volume 11"), r.calls)
    }

    @Test
    fun `summary line`() {
        assertEquals("Can't reach Kitchen", Shortcuts.summary(null, "Kitchen"))
        assertEquals("Song · Band", Shortcuts.summary(status(TransportState.PLAYING, title = "Song", artist = "Band"), "K"))
        assertEquals("Paused · Song", Shortcuts.summary(status(TransportState.PAUSED, title = "Song"), "K"))
        assertEquals("Stopped · Volume 20", Shortcuts.summary(status(TransportState.STOPPED), "K"))
        assertEquals("Nothing playing · Muted", Shortcuts.summary(status(TransportState.NO_MEDIA, muted = true), "K"))
        assertEquals("", Shortcuts.summary(status(TransportState.UNKNOWN, volume = null, muted = null), "K"))
    }

    @Test
    fun `speaker routes are encoded and checked`() {
        val route = Shortcuts.speakerRoute(Brand.CAST, "ip:192.168.1.5:8009")
        assertEquals("speaker/CAST/ip%3A192.168.1.5%3A8009", route)
        assertTrue(Shortcuts.isSpeakerRoute(route))
        assertTrue(Shortcuts.isSpeakerRoute(Shortcuts.speakerRoute(Brand.SONOS, "RINCON_X 1")))
        assertFalse(Shortcuts.isSpeakerRoute("debug"))
        assertFalse(Shortcuts.isSpeakerRoute("speaker/NOPE/x"))
        assertFalse(Shortcuts.isSpeakerRoute("speaker/SONOS/a/b"))
        assertFalse(Shortcuts.isSpeakerRoute(null))
    }
}
