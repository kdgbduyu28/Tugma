package com.timborja.tugma.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MuteAllTest {
    private class Speaker(var muted: Boolean, val reachable: Boolean = true) : PlaybackControl {
        override val capabilities = setOf(Capability.MUTE)
        override suspend fun status() = PlaybackStatus(10, 100, muted, TransportState.PLAYING)
        override suspend fun setVolume(volume: Int) = Unit
        override suspend fun setMuted(muted: Boolean) { if (!reachable) throw java.io.IOException("timeout"); this.muted = muted }
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
    }

    @Test
    fun `unmute restores only what mute all muted`() = runBlocking {
        val speakers = mapOf("a" to Speaker(false), "b" to Speaker(true), "c" to Speaker(false, reachable = false))
        val done = MuteAll.mute(speakers) { speakers.getValue(it).muted }
        assertEquals(listOf("a"), done)
        assertEquals(true, speakers.getValue("a").muted)

        assertEquals(listOf("a"), MuteAll.unmute(done + "gone") { speakers[it] })
        assertEquals(false, speakers.getValue("a").muted)
        assertEquals(true, speakers.getValue("b").muted, "the one muted by hand stays muted")
    }
}
