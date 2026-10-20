package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.bt.soundcore.AmbientMode
import com.timborja.tugma.bt.soundcore.Battery
import com.timborja.tugma.bt.soundcore.EqKind
import com.timborja.tugma.bt.soundcore.NoiseCancelingMode
import com.timborja.tugma.bt.soundcore.SoundModes
import com.timborja.tugma.bt.soundcore.SoundcoreDevice
import com.timborja.tugma.bt.soundcore.SoundcoreEq
import com.timborja.tugma.bt.soundcore.SoundcoreModel
import com.timborja.tugma.bt.soundcore.SoundcoreModels
import com.timborja.tugma.bt.soundcore.SoundcoreState
import com.timborja.tugma.bt.soundcore.TransparencyMode
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SoundcoreViewModel(private val c: AppContainer, address: String, name: String) : ViewModel() {
    val candidate = c.soundcore.candidate(address, name)
    var model by mutableStateOf(c.soundcore.modelFor(candidate))
        private set
    var connecting by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    private var device: SoundcoreDevice? = null
    private val idle = MutableStateFlow(SoundcoreState())
    var state: StateFlow<SoundcoreState> by mutableStateOf(idle)
        private set

    /** Custom EQ draft in tenths of a dB. */
    val customEq = mutableStateListOf(*Array(8) { 0 })

    fun pickModel(m: SoundcoreModel) {
        model = m
        c.soundcore.rememberModel(candidate.address, m)
        disconnect()
    }

    fun connect() {
        val m = model ?: return
        connecting = true
        viewModelScope.launch {
            runCatching { c.soundcore.connect(candidate.address, m) }
                .onSuccess { d ->
                    device = d
                    state = d.state
                    d.start(viewModelScope)
                }
                .onFailure { message = "Couldn't connect: ${it.message}" }
            connecting = false
        }
    }

    fun disconnect() {
        device?.close()
        device = null
        state = idle
    }

    fun setModes(m: SoundModes) = act { setSoundModes(m) }
    fun setPreset(id: Int) = act { setEqualizer(id, SoundcoreEq.preset(id)!!.tenths) }
    fun applyCustom() = act { setEqualizer(SoundcoreEq.CUSTOM_PRESET_ID, customEq.toList()) }
    fun refresh() = act { requestAll() }

    private fun act(block: suspend SoundcoreDevice.() -> Unit) {
        val d = device ?: return
        viewModelScope.launch { runCatching { d.block() }.onFailure { message = it.message } }
    }

    override fun onCleared() = disconnect()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundcoreScreen(address: String, name: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = "soundcore-$address") { SoundcoreViewModel(it, address, name) }
    val state by vm.state.collectAsState()
    val model = vm.model

    ScreenScaffold(name, onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IssueList(
                listOf(
                    Issue(
                        Severity.WARN,
                        "Experimental. Soundcore support is ported from OpenSCQ30 and hasn't been tested on real " +
                            "Soundcore hardware by Tugma yet. If something looks wrong, share the Debug log.",
                    ),
                ),
            )

            // Model: guessed from the Bluetooth name / service UUID, overridable.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(model?.let { "${it.name} (${it.id})" } ?: "Unknown: pick yours", style = MaterialTheme.typography.titleMedium)
                        }
                        ModelMenu(onPick = vm::pickModel)
                    }
                    model?.let { Text("Supported in Tugma: ${it.ported.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
                    vm.candidate.uuidHint?.let { Text("Service UUID hint: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    Text(vm.candidate.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Connection.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.connected) {
                    OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                    OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
                } else {
                    Button(onClick = vm::connect, enabled = model != null && !vm.connecting) { Text(if (vm.connecting) "Connecting…" else "Connect") }
                }
            }
            if (vm.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (state.connected && model != null) {
                ConnectedSettings(vm, state, model)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedSettings(vm: SoundcoreViewModel, state: SoundcoreState, model: SoundcoreModel) {
    SectionLabel("Battery")
    if (state.left == null && state.right == null) {
        Text("Waiting for the device to report…", style = MaterialTheme.typography.bodyMedium)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            state.left?.let { BatteryText(if (state.right != null) "Left" else "Battery", it) }
            state.right?.let { BatteryText("Right", it) }
        }
    }

    if (model.soundModesV1) {
        val modes = state.soundModes ?: SoundModes()
        SectionLabel("Sound mode")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmbientMode.entries.forEach { m ->
                FilterChip(selected = modes.ambient == m, onClick = { vm.setModes(modes.copy(ambient = m)) }, label = { Text(m.label) })
            }
        }
        if (modes.ambient == AmbientMode.NOISE_CANCELING) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoiseCancelingMode.entries.filter { it != NoiseCancelingMode.CUSTOM }.forEach { m ->
                    FilterChip(selected = modes.noiseCanceling == m, onClick = { vm.setModes(modes.copy(noiseCanceling = m)) }, label = { Text(m.label) })
                }
            }
        }
        if (modes.ambient == AmbientMode.TRANSPARENCY) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransparencyMode.entries.forEach { m ->
                    FilterChip(selected = modes.transparency == m, onClick = { vm.setModes(modes.copy(transparency = m)) }, label = { Text(m.label) })
                }
            }
        }
    }

    if (model.eq != EqKind.NONE) {
        SectionLabel("Earbud equalizer")
        Text(
            "Stored on the ${if (model.eq == EqKind.TWS) "earbuds" else "headphones"}, so it applies on every phone. " +
                "Current: ${state.eq?.presetName ?: "unknown"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SoundcoreEq.PRESETS.forEach { p ->
                FilterChip(selected = state.eq?.presetId == p.id, onClick = { vm.setPreset(p.id) }, label = { Text(p.name) })
            }
        }
        Text("Custom", style = MaterialTheme.typography.titleSmall)
        SoundcoreEq.BANDS_HZ.forEachIndexed { i, hz ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (hz >= 1000) "${hz / 1000.0}k" else "$hz", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                Slider(
                    value = vm.customEq[i].toFloat(),
                    onValueChange = { vm.customEq[i] = (it / 5).toInt() * 5 }, // 0.5 dB steps
                    valueRange = SoundcoreEq.MIN_TENTHS.toFloat()..SoundcoreEq.MAX_TENTHS.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                Text("%+.1f".format(vm.customEq[i] / 10.0), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            }
        }
        Button(onClick = vm::applyCustom) { Text("Send custom EQ") }
    }

    SectionLabel("Device")
    Text("Firmware: ${state.firmware.ifEmpty { listOf("?") }.joinToString(" / ")}", style = MaterialTheme.typography.bodyMedium)
    Text("Serial: ${state.serial ?: "?"}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    if (state.unknownCommands.isNotEmpty()) {
        Text(
            "Packets not decoded for this model yet: ${state.unknownCommands.sorted().joinToString(", ")}. They're in the Debug log.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryText(label: String, b: Battery) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${b.percent}%" + if (b.charging) " · charging" else "", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ModelMenu(onPick: (SoundcoreModel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SoundcoreModels.all.forEach { m ->
                DropdownMenuItem(text = { Text("${m.name} · ${m.id}") }, onClick = { open = false; onPick(m) })
            }
        }
    }
}
