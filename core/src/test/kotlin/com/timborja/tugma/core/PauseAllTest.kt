package com.timborja.tugma.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PauseAllTest {
    private class Speaker(var transport: TransportState, val reachable: Boolean = true) : PlaybackControl {
        override val capabilities = setOf(Capability.TRANSPORT)
        override suspend fun status(): PlaybackStatus {
            if (!reachable) throw java.io.IOException("timeout")
            return PlaybackStatus(10, 100, false, transport)
        }
        override suspend fun setVolume(volume: Int) = Unit
        override suspend fun setMuted(muted: Boolean) = Unit
        override suspend fun play() { if (!reachable) throw java.io.IOException("timeout"); transport = TransportState.PLAYING }
        override suspend fun pause() { transport = TransportState.PAUSED }
        override suspend fun stop() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
    }

    private class Source(override val brand: Brand, private val speakers: Map<SpeakerSummary, Speaker>) : SpeakerSource {
        override val summaries: StateFlow<SourceState> = MutableStateFlow(SourceState(speakers = speakers.keys.toList()))
        override suspend fun refresh() = Unit
        override fun playback(id: String): PlaybackControl? = speakers.entries.firstOrNull { it.key.id == id }?.value
    }

    private fun summary(id: String, brand: Brand = Brand.UPNP, hidden: Boolean = false, caps: Set<Capability> = setOf(Capability.TRANSPORT)) =
        SpeakerSummary(id, brand, id, "Demo", "192.168.50.$id", 80, caps, hidden = hidden)

    @Test
    fun `pauses only what's playing and resumes exactly those`() = runBlocking {
        val a = Speaker(TransportState.PLAYING)
        val b = Speaker(TransportState.STOPPED)
        val c = Speaker(TransportState.PLAYING, reachable = false)
        val hidden = Speaker(TransportState.PLAYING)
        val volumeOnly = Speaker(TransportState.PLAYING)
        val source = Source(
            Brand.UPNP,
            mapOf(
                summary("1") to a, summary("2") to b, summary("3") to c,
                summary("4", hidden = true) to hidden, summary("5", caps = setOf(Capability.VOLUME)) to volumeOnly,
            ),
        )

        val paused = PauseAll.pause(PauseAll.targets(listOf(source)))
        assertEquals(listOf(PausedSpeaker(Brand.UPNP, "1")), paused)
        assertEquals(TransportState.PAUSED, a.transport)
        assertEquals(TransportState.STOPPED, b.transport)
        assertEquals(TransportState.PLAYING, hidden.transport, "hidden speakers are left alone")

        val resumed = PauseAll.resume(paused + PausedSpeaker(Brand.UPNP, "gone")) { source.playback(it.id) }
        assertEquals(1, resumed)
        assertEquals(TransportState.PLAYING, a.transport)
        assertEquals(TransportState.STOPPED, b.transport)
    }
}
