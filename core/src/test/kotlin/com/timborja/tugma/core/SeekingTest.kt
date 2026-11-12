package com.timborja.tugma.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekingTest {
    @Test
    fun `media times parse, format and display`() {
        assertEquals(3725, MediaTime.parse("1:02:05"))
        assertEquals(65, MediaTime.parse(" 0:01:05.250 "))
        assertNull(MediaTime.parse("NOT_IMPLEMENTED"))
        assertNull(MediaTime.parse("0:-1:00"))
        assertNull(MediaTime.parse("1:05"))
        assertEquals("0:03:35", MediaTime.format(215))
        assertEquals("0:00:00", MediaTime.format(-3))
        assertEquals("3:35", MediaTime.clock(215))
        assertEquals("1:02:05", MediaTime.clock(3725))
    }

    @Test
    fun `only positions with a length are seekable`() {
        assertTrue(MediaPosition(10, 200).seekable)
        assertFalse(MediaPosition(10, null).seekable)
        assertFalse(MediaPosition(null, 0).seekable)
    }
}
