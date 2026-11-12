package com.timborja.tugma.core

enum class RepeatMode(val label: String) { OFF("Off"), ALL("All"), ONE("One") }

/** Shuffle and repeat together, as every brand reports them. */
data class ShuffleRepeat(val shuffle: Boolean, val repeat: RepeatMode)

/** Speakers whose own player can shuffle and repeat (a queue or playlist, not radio). */
interface PlayModeControl {
    /** Not every brand can combine shuffle with repeat; the UI only offers what's here. */
    fun supports(mode: ShuffleRepeat): Boolean

    /** Null when the speaker didn't answer or reports a mode Tugma doesn't know. */
    suspend fun playMode(): ShuffleRepeat?

    suspend fun setPlayMode(mode: ShuffleRepeat)
}

/** What the shuffle and repeat buttons ask for next, skipping combinations the speaker can't do. */
object PlayModeSteps {
    fun toggleShuffle(current: ShuffleRepeat, control: PlayModeControl): ShuffleRepeat? =
        listOf(current.copy(shuffle = !current.shuffle), ShuffleRepeat(!current.shuffle, RepeatMode.OFF)).firstOrNull(control::supports)

    fun nextRepeat(current: ShuffleRepeat, control: PlayModeControl): ShuffleRepeat? {
        val order = RepeatMode.entries
        return (1 until order.size).map { order[(current.repeat.ordinal + it) % order.size] }
            .map { current.copy(repeat = it) }
            .firstOrNull(control::supports)
    }
}
