package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.SoundSetting
import com.timborja.tugma.sonos.SoundState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Sonos bass/treble/loudness and soundbar EQ. Like volume, these apply immediately: no confirm step. */
class SonosSoundViewModel(private val c: AppContainer, private val id: String, private val homeTheater: Boolean) : ViewModel() {
    var state by mutableStateOf<SoundState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val dragging = mutableStateMapOf<SoundSetting, Float>()

    fun load() {
        val sound = c.sonos.sound(id) ?: return
        viewModelScope.launch {
            runCatching { sound.read(homeTheater) }.onSuccess { state = it; error = null }.onFailure { error = it.message }
        }
    }

    fun set(s: SoundSetting, value: Int) {
        val sound = c.sonos.sound(id) ?: return
        state = state?.let { SoundState(it.values + (s to value)) }
        viewModelScope.launch {
            runCatching { sound.set(s, value) }.onFailure { error = "${s.label}: ${it.message}"; load() }
        }
    }

    fun commit(s: SoundSetting) {
        dragging.remove(s)?.let { set(s, it.roundToInt()) }
    }

    fun reset() {
        val sound = c.sonos.sound(id) ?: return
        val current = state ?: return
        viewModelScope.launch {
            runCatching { sound.reset(current) }.onFailure { error = it.message }
            load()
        }
    }
}

@Composable
fun SonosSoundSection(id: String, homeTheater: Boolean) {
    val vm = containerViewModel(key = "sound-$id") { SonosSoundViewModel(it, id, homeTheater) }
    LaunchedEffect(id) { vm.load() }
    val state = vm.state

    SectionLabel("Sound")
    vm.error?.let { Text("Couldn't read sound settings: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (state == null) {
        Text("Reading…", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        state.supported.forEach { s ->
            if (s.isSwitch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.on(s) == true, onCheckedChange = { vm.set(s, if (it) 1 else 0) })
                }
            } else {
                val range = s.range!!
                val v = vm.dragging[s] ?: state[s]?.toFloat() ?: 0f
                Text(s.label, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = v,
                        onValueChange = { vm.dragging[s] = it },
                        onValueChangeFinished = { vm.commit(s) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = range.last - range.first - 1,
                        modifier = Modifier.weight(1f).semantics { contentDescription = s.label },
                    )
                    val n = v.roundToInt()
                    Text(if (n > 0) "+$n" else "$n", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                }
            }
        }
    }
    OutlinedButton(onClick = vm::reset) { Text("Reset levels") }
    if (homeTheater) {
        Text(
            "Settings the soundbar doesn't report (for example Sub level without a Sub) are hidden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
