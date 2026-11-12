package com.timborja.tugma.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayModesTest {
    /** Like a standard UPnP renderer: shuffle can't be combined with repeat. */
    private val standard = object : PlayModeControl {
        override fun supports(mode: ShuffleRepeat) = !mode.shuffle || mode.repeat == RepeatMode.OFF
        override suspend fun playMode(): ShuffleRepeat? = null
        override suspend fun setPlayMode(mode: ShuffleRepeat) = Unit
    }
    private val everything = object : PlayModeControl {
        override fun supports(mode: ShuffleRepeat) = true
        override suspend fun playMode(): ShuffleRepeat? = null
        override suspend fun setPlayMode(mode: ShuffleRepeat) = Unit
    }

    @Test
    fun `repeat cycles off, all, one`() {
        val off = ShuffleRepeat(false, RepeatMode.OFF)
        assertEquals(ShuffleRepeat(false, RepeatMode.ALL), PlayModeSteps.nextRepeat(off, everything))
        assertEquals(ShuffleRepeat(false, RepeatMode.ONE), PlayModeSteps.nextRepeat(ShuffleRepeat(false, RepeatMode.ALL), everything))
        assertEquals(off, PlayModeSteps.nextRepeat(ShuffleRepeat(false, RepeatMode.ONE), everything))
        assertEquals(ShuffleRepeat(true, RepeatMode.ALL), PlayModeSteps.nextRepeat(ShuffleRepeat(true, RepeatMode.OFF), everything))
    }

    @Test
    fun `steps skip combinations the speaker can't do`() {
        assertNull(PlayModeSteps.nextRepeat(ShuffleRepeat(true, RepeatMode.OFF), standard), "shuffle on: no repeat is possible")
        assertEquals(ShuffleRepeat(true, RepeatMode.OFF), PlayModeSteps.toggleShuffle(ShuffleRepeat(false, RepeatMode.ALL), standard), "shuffle drops repeat")
        assertEquals(ShuffleRepeat(false, RepeatMode.OFF), PlayModeSteps.toggleShuffle(ShuffleRepeat(true, RepeatMode.OFF), standard))
        assertEquals(ShuffleRepeat(true, RepeatMode.ONE), PlayModeSteps.toggleShuffle(ShuffleRepeat(false, RepeatMode.ONE), everything))
    }
}
