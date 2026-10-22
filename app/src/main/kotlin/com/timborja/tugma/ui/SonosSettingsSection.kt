package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.SonosSettings
import com.timborja.tugma.sonos.SpeakerSettings
import kotlinx.coroutines.launch

class SonosSettingsViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    var state by mutableStateOf<SpeakerSettings?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var renaming by mutableStateOf(false)

    private fun settings(): SonosSettings? = c.sonos.settings(id)

    fun load() {
        val s = settings() ?: return
        viewModelScope.launch { runCatching { s.read() }.onSuccess { state = it } }
    }

    private fun write(done: String, block: suspend SonosSettings.() -> Unit) {
        val s = settings() ?: return
        viewModelScope.launch {
            runCatching { s.block() }.onSuccess { message = done }.onFailure { message = it.message ?: "Failed" }
            load()
        }
    }

    fun light(on: Boolean) = write(if (on) "Status light on" else "Status light off") { setStatusLight(on) }
    fun touch(enabled: Boolean) = write(if (enabled) "Touch controls on" else "Touch controls locked") { setButtonsLocked(!enabled) }
    fun trueplay(on: Boolean) = write(if (on) "Trueplay on" else "Trueplay off") { setTrueplay(on) }

    fun rename(name: String) {
        renaming = false
        val s = settings() ?: return
        viewModelScope.launch {
            runCatching { s.rename(name) }.onSuccess { message = "Renamed to ${name.trim()}" }.onFailure { message = it.message ?: "Rename failed" }
            c.sonos.refresh()
            load()
        }
    }
}

/** Status light, touch controls, Trueplay and the room name. Small, reversible settings: no confirm step. */
@Composable
fun SonosSettingsSection(id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "sonos-settings-$id") { SonosSettingsViewModel(it, id) }
    LaunchedEffect(id) { vm.load() }
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }
    val s = vm.state ?: return

    SectionLabel("Speaker settings")
    @Composable
    fun toggle(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
        if (value == null) return
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
    toggle("Status light", s.statusLight, vm::light)
    toggle("Touch controls", s.buttonsLocked?.not(), vm::touch)
    if (s.trueplayAvailable == true) toggle("Trueplay tuning", s.trueplayEnabled, vm::trueplay)
    OutlinedButton(onClick = { vm.renaming = true }) { Text("Rename room…") }

    if (vm.renaming) {
        var name by remember { mutableStateOf(s.roomName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { vm.renaming = false },
            title = { Text("Rename room") },
            text = { OutlinedTextField(value = name, onValueChange = { if (it.length <= SonosSettings.MAX_NAME) name = it }, singleLine = true, label = { Text("Room name") }) },
            confirmButton = { TextButton(onClick = { vm.rename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { vm.renaming = false }) { Text("Cancel") } },
        )
    }
}
