package com.timborja.tugma.remote

/**
 * While a speaker's screen is open, the phone's volume buttons change that speaker instead of
 * the phone. The screen registers a handler; the activity forwards key presses to it.
 */
object VolumeKeys {
    @Volatile
    private var handler: ((up: Boolean) -> Boolean)? = null

    fun set(h: (up: Boolean) -> Boolean) { handler = h }

    /** Clears only if [h] is still the current handler, so a newer screen's handler survives. */
    fun clear(h: (up: Boolean) -> Boolean) { if (handler === h) handler = null }

    /** True when a speaker took the press; false lets the phone handle it as usual. */
    fun dispatch(up: Boolean): Boolean = handler?.invoke(up) ?: false
}
