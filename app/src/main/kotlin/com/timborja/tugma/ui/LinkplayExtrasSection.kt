package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.linkplay.LinkplayExtras
import com.timborja.tugma.linkplay.LinkplayPreset
import kotlinx.coroutines.launch

/** Linkplay presets and the speaker's own EQ. Everyday controls: no confirm step. */
class LinkplayExtrasViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    var presets by mutableStateOf<List<LinkplayPreset>?>(null)
        private set
    var eqOn by mutableStateOf<Boolean?>(null)
        private set
    var eqPresets by mutableStateOf<List<String>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)

    private fun extras(): LinkplayExtras? = c.linkplay.extras(id)

    fun load() {
        val x = extras() ?: return
        viewModelScope.launch {
            presets = runCatching { x.presets() }.getOrNull()
            eqOn = runCatching { x.eqEnabled() }.getOrNull()
            eqPresets = if (eqOn != null) runCatching { x.eqPresets() }.getOrDefault(emptyList()) else emptyList()
        }
    }

    private fun run(done: String, block: suspend LinkplayExtras.() -> Unit) {
        val x = extras() ?: return
        viewModelScope.launch {
            runCatching { x.block() }.onSuccess { message = done }.onFailure { message = it.message ?: "Failed" }
            eqOn = runCatching { x.eqEnabled() }.getOrNull()
        }
    }

    fun play(n: Int, name: String) = run("Playing $name") { playPreset(n) }
    fun setEq(on: Boolean) = run(if (on) "EQ on" else "EQ off") { setEq(on) }
    fun loadEq(name: String) = run("EQ: $name") { loadEq(name) }
    fun input(i: com.timborja.tugma.linkplay.LinkplayInput) = run("Switched to ${i.label}") { switchInput(i) }

    var renaming by mutableStateOf(false)

    fun rename(name: String) {
        renaming = false
        val x = extras() ?: return
        viewModelScope.launch {
            runCatching { x.rename(name) }.onSuccess { message = "Renamed to ${name.trim()}" }.onFailure { message = it.message ?: "Rename failed" }
            c.linkplay.refresh()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkplayExtrasSection(id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "lp-extras-$id") { LinkplayExtrasViewModel(it, id) }
    LaunchedEffect(id) { vm.load() }
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }

    SectionLabel("Presets")
    val presets = vm.presets
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (presets.isNullOrEmpty()) {
            (1..6).forEach { n -> AssistChip(onClick = { vm.play(n, "preset $n") }, label = { Text("$n") }) }
        } else {
            presets.forEach { p -> AssistChip(onClick = { vm.play(p.number, p.name) }, label = { Text("${p.number}. ${p.name}") }) }
        }
    }
    Text(
        if (presets.isNullOrEmpty()) "This speaker doesn't list preset names; the numbers play whatever is saved in its app."
        else "Presets are saved in the speaker maker's app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionLabel("Input (experimental)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.timborja.tugma.linkplay.LinkplayInput.entries.forEach { i -> AssistChip(onClick = { vm.input(i) }, label = { Text(i.label) }) }
    }
    Text("Not every model has every input; the speaker refuses ones it lacks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    androidx.compose.material3.OutlinedButton(onClick = { vm.renaming = true }) { Text("Rename speaker…") }
    if (vm.renaming) {
        var name by androidx.compose.runtime.remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.renaming = false },
            title = { Text("Rename speaker") },
            text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { if (it.length <= 32) name = it }, singleLine = true, label = { Text("New name") }) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.rename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.renaming = false }) { Text("Cancel") } },
        )
    }

    val eq = vm.eqOn ?: return
    SectionLabel("Speaker EQ (experimental)")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("EQ", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = eq, onCheckedChange = vm::setEq)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        vm.eqPresets.forEach { name -> FilterChip(selected = false, onClick = { vm.loadEq(name) }, label = { Text(name) }) }
    }
}
