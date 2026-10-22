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
import androidx.compose.material3.Switch
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
import com.timborja.tugma.bt.redmibuds.AmbientSound
import com.timborja.tugma.bt.redmibuds.BudBattery
import com.timborja.tugma.bt.redmibuds.RedmiBudsDevice
import com.timborja.tugma.bt.redmibuds.RedmiBudsModel
import com.timborja.tugma.bt.redmibuds.RedmiBudsModels
import com.timborja.tugma.bt.redmibuds.RedmiBudsState
import com.timborja.tugma.bt.redmibuds.RedmiConfig
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RedmiBudsViewModel(private val c: AppContainer, val address: String, name: String) : ViewModel() {
    var model by mutableStateOf(c.redmi.modelFor(address, name))
        private set
    var connecting by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    private var device: RedmiBudsDevice? = null
    private val idle = MutableStateFlow(RedmiBudsState())
    var state: StateFlow<RedmiBudsState> by mutableStateOf(idle)
        private set
    /** Custom EQ draft, whole dB per band. */
    val customEq = mutableStateListOf(*Array(10) { 0 })

    fun pickModel(m: RedmiBudsModel) {
        model = m
        c.redmi.rememberModel(address, m)
        disconnect()
    }

    fun connect() {
        val m = model ?: return
        connecting = true
        viewModelScope.launch {
            runCatching { c.redmi.connect(address, m) }
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

    fun act(block: suspend RedmiBudsDevice.() -> Unit) {
        val d = device ?: return
        viewModelScope.launch { runCatching { d.block() }.onFailure { message = it.message } }
    }

    override fun onCleared() = disconnect()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RedmiBudsScreen(address: String, name: String, onBack: () -> Unit) {
    val vm = containerViewModel(key = "redmi-$address") { RedmiBudsViewModel(it, address, name) }
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
                        "Experimental. Redmi Buds support is ported from Gadgetbridge and hasn't been tested on real " +
                            "earbuds by Tugma yet. If something looks wrong, share the Debug log.",
                    ),
                ),
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(model?.name ?: "Unknown: pick yours", style = MaterialTheme.typography.titleMedium)
                        }
                        RedmiModelMenu(vm::pickModel)
                    }
                    model?.let { Text("Supported in Tugma: ${it.features.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
                    Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.connected) {
                    OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = vm::connect, enabled = model != null && !vm.connecting) { Text(if (vm.connecting) "Connecting…" else "Connect") }
                }
            }
            if (vm.connecting || (state.connected && !state.authenticated)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.connected) Text("Authenticating with the earbuds…", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (state.authenticated && model != null) RedmiSettings(vm, state, model)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RedmiSettings(vm: RedmiBudsViewModel, s: RedmiBudsState, m: RedmiBudsModel) {
    SectionLabel("Battery")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Bud("Left", s.left)
        Bud("Right", s.right)
        Bud("Case", s.case)
    }

    if (m.ambientModes.isNotEmpty()) {
        SectionLabel("Noise control")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.ambientModes.forEach { a -> FilterChip(selected = s.ambient == a, onClick = { vm.act { setAmbient(a) } }, label = { Text(a.label) }) }
        }
        if (s.ambient == AmbientSound.NOISE_CANCELLING && m.ancStrengths.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.ancStrengths.forEach { a -> FilterChip(selected = s.ancStrength == a, onClick = { vm.act { setAncStrength(a) } }, label = { Text(a.label) }) }
            }
        }
        if (s.ambient == AmbientSound.TRANSPARENCY && m.transparencyStrengths.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.transparencyStrengths.forEach { t -> FilterChip(selected = s.transparencyStrength == t, onClick = { vm.act { setTransparencyStrength(t) } }, label = { Text(t.label) }) }
            }
        }
    }

    if (m.eqPresets.isNotEmpty() || m.customEq) {
        SectionLabel("Earbud equalizer")
        if (m.eqPresets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.eqPresets.forEach { p -> FilterChip(selected = s.eqPreset == p, onClick = { vm.act { setEqPreset(p) } }, label = { Text(p.label) }) }
            }
        }
        if (m.customEq) {
            Text("Custom curve" + (s.eqCurve?.let { " · on earbuds: ${it.joinToString(" ")}" } ?: ""), style = MaterialTheme.typography.titleSmall)
            RedmiConfig.EQ_BANDS_HZ.forEachIndexed { i, hz ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    Slider(value = vm.customEq[i].toFloat(), onValueChange = { vm.customEq[i] = it.toInt() }, valueRange = -6f..6f, steps = 11, modifier = Modifier.weight(1f))
                    Text("%+d".format(vm.customEq[i]), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                }
            }
            Button(onClick = { vm.act { setCustomEq(vm.customEq.toList()) } }) { Text("Send custom EQ") }
        }
    }

    if (m.toggles.isNotEmpty() || m.earDetection) {
        SectionLabel("Settings")
        if (m.earDetection) ToggleRow("Pause when an earbud is removed", s.earDetection == true) { on -> vm.act { setEarDetection(on) } }
        m.toggles.forEach { t -> ToggleRow(t.label, s.toggles[t] == true) { on -> vm.act { setToggle(t, on) } } }
    }

    if (m.findEarbuds) {
        SectionLabel("Find earbuds")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.act { findEarbuds(true) } }) { Text("Play sound") }
            OutlinedButton(onClick = { vm.act { findEarbuds(false) } }) { Text("Stop") }
        }
    }

    SectionLabel("Device")
    Text("Firmware: ${listOfNotNull(s.firmware, s.firmware2).joinToString(" / ").ifEmpty { "?" }}", style = MaterialTheme.typography.bodyMedium)
    s.hardware?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun Bud(label: String, b: BudBattery?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(b?.let { "${it.level}%" + if (it.charging) " ⚡" else "" } ?: "–", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RedmiModelMenu(onPick: (RedmiBudsModel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RedmiBudsModels.all.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { open = false; onPick(m) }) }
        }
    }
}
