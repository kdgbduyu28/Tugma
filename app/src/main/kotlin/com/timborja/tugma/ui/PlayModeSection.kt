package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.PlayModeControl
import com.timborja.tugma.core.PlayModeSteps
import com.timborja.tugma.core.RepeatMode
import com.timborja.tugma.core.ShuffleRepeat
import kotlinx.coroutines.launch

/** Shuffle and repeat for brands with a generic play mode (DLNA, Linkplay). Sonos has its own, with crossfade. */
class PlayModeViewModel(private val c: AppContainer, private val brand: Brand, private val id: String) : ViewModel() {
    var mode by mutableStateOf<ShuffleRepeat?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    val control: PlayModeControl? get() = c.source(brand)?.playback(id) as? PlayModeControl

    suspend fun read() {
        control?.let { p -> runCatching { p.playMode() }.onSuccess { mode = it } }
    }

    fun set(next: ShuffleRepeat?) {
        val p = control ?: return
        if (next == null) { message = "This speaker can't do that combination"; return }
        viewModelScope.launch {
            runCatching { p.setPlayMode(next) }.onFailure { message = "Shuffle/repeat: ${it.message}" }
            read()
        }
    }
}

/** Hidden until the speaker reports a play mode; renderers that play single links usually don't. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayModeSection(brand: Brand, id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "playmode-$brand-$id") { PlayModeViewModel(it, brand, id) }
    val control = vm.control ?: return
    LaunchedEffect(id) { vm.read() }
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }
    val mode = vm.mode ?: return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode.shuffle, onClick = { vm.set(PlayModeSteps.toggleShuffle(mode, control)) }, label = { Text("Shuffle") })
        FilterChip(
            selected = mode.repeat != RepeatMode.OFF,
            onClick = { vm.set(PlayModeSteps.nextRepeat(mode, control)) },
            label = { Text("Repeat: ${mode.repeat.label}") },
        )
    }
}
