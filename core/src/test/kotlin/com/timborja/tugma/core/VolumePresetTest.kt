package com.timborja.tugma.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VolumePresetTest {
    private class Speaker(val reachable: Boolean = true) : PlaybackControl {
        var volume: Int? = null
        override val capabilities = setOf(Capability.VOLUME)
        override suspend fun status() = PlaybackStatus(volume, 100, false, TransportState.UNKNOWN)
        override suspend fun setVolume(volume: Int) { if (!reachable) throw java.io.IOException("timeout"); this.volume = volume }
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
    }

    @Test
    fun `keys round trip and reject junk`() {
        assertEquals("CAST|ip:192.168.1.5:8009", SpeakerKey.of(Brand.CAST, "ip:192.168.1.5:8009"))
        assertEquals(Brand.CAST to "ip:192.168.1.5:8009", SpeakerKey.parse("CAST|ip:192.168.1.5:8009"))
        assertNull(SpeakerKey.parse("NOPE|x"))
        assertNull(SpeakerKey.parse("CAST|"))
        assertNull(SpeakerKey.parse("CAST"))
    }

    @Test
    fun `sets each volume, clamped, and counts the ones that took it`() = runBlocking {
        val a = Speaker()
        val b = Speaker(reachable = false)
        val speakers = mapOf("UPNP|a" to a, "UPNP|b" to b)
        val n = VolumePreset.apply(mapOf("UPNP|a" to 140, "UPNP|b" to 20, "UPNP|gone" to 10)) { speakers[it] }
        assertEquals(1, n)
        assertEquals(100, a.volume)
    }
}
