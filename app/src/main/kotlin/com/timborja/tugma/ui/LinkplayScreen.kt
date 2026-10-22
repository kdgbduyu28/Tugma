package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.ConfirmedPlan
import com.timborja.tugma.linkplay.LinkplayChannel
import com.timborja.tugma.linkplay.LinkplayDevice
import com.timborja.tugma.linkplay.LinkplayPlanner
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import kotlinx.coroutines.launch

class LinkplayViewModel(private val c: AppContainer) : ViewModel() {
    val devices = c.linkplay.devices
    val demo = c.settings.demoMode
    var pending by mutableStateOf<CommandPlan?>(null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var left by mutableStateOf<String?>(null)
    var right by mutableStateOf<String?>(null)
    var newHost by mutableStateOf<String?>(null)
    val newGuests = mutableStateListOf<String>()

    init {
        if (!c.linkplay.summaries.value.loadedOnce) refresh()
    }

    fun refresh() {
        viewModelScope.launch { c.linkplay.refresh() }
    }

    fun execute(confirmed: ConfirmedPlan) {
        pending = null
        busy = true
        viewModelScope.launch {
            c.linkplay.execute(confirmed)
                .onSuccess { message = "Done: ${confirmed.plan.title}"; newGuests.clear() }
                .onFailure { message = it.message ?: "Failed" }
            busy = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkplayScreen(onBack: () -> Unit) {
    val vm = containerViewModel { LinkplayViewModel(it) }
    val devices by vm.devices.collectAsState()
    val demo by vm.demo.collectAsState()
    val standalone = devices.filter { !it.isGuest && it.guests.isEmpty() }
    fun byId(id: String?) = devices.firstOrNull { it.uuid == id }

    ScreenScaffold("Linkplay groups", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Arylic, WiiM, Audio Pro and other Linkplay speakers can group across models. Left/right channel control is experimental.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (devices.isEmpty()) {
                Text("No Linkplay speakers found yet. Pull to refresh on the Speakers tab.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = vm::refresh) { Text("Scan again") }
            }

            // Cross-model stereo pair.
            if (standalone.size >= 2) {
                SectionLabel("Stereo pair")
                DevicePicker("Left", standalone, vm.left) { vm.left = it }
                DevicePicker("Right", standalone.filter { it.uuid != vm.left }, vm.right) { vm.right = it }
                val l = byId(vm.left)
                val r = byId(vm.right)
                Button(onClick = { if (l != null && r != null) vm.pending = LinkplayPlanner.stereoPair(l, r) }, enabled = l != null && r != null && l != r && !vm.busy) {
                    Text("Review stereo pair")
                }
            }

            // Existing groups.
            devices.filter { it.guests.isNotEmpty() }.forEach { host ->
                SectionLabel("Group: ${host.name}")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChannelRow("${host.name} (host)", host.channel) { ch -> vm.pending = LinkplayPlanner.setChannel(host, host, null, ch) }
                        host.guests.forEach { g ->
                            ChannelRow(g.name, g.channel) { ch -> vm.pending = LinkplayPlanner.setChannel(host, null, g, ch) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { vm.pending = LinkplayPlanner.kick(host, g.ip, g.name) }) { Text("Remove") }
                            }
                        }
                        OutlinedButton(onClick = { vm.pending = LinkplayPlanner.ungroup(host) }) { Text("Ungroup all") }
                    }
                }
            }

            // New multiroom group.
            if (standalone.size >= 2) {
                SectionLabel("New group")
                DevicePicker("Host", standalone, vm.newHost) { vm.newHost = it; vm.newGuests.remove(it) }
                standalone.filter { it.uuid != vm.newHost }.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.uuid in vm.newGuests, onCheckedChange = { on -> if (on) vm.newGuests.add(d.uuid) else vm.newGuests.remove(d.uuid) })
                        Text("${d.name} · ${d.hardware ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val host = byId(vm.newHost)
                Button(
                    onClick = { if (host != null) vm.pending = LinkplayPlanner.group(host, vm.newGuests.mapNotNull(::byId)) },
                    enabled = host != null && vm.newGuests.isNotEmpty() && !vm.busy,
                ) { Text("Review group") }
            }

            if (devices.any { it.isGuest } && devices.none { it.guests.isNotEmpty() }) {
                IssueList(listOf(Issue(Severity.INFO, "Some speakers are guests of a host Tugma can't see. Refresh, or check the host is on this network.")))
            }
        }
    }

    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = { vm.pending = null }, demo = demo) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelRow(label: String, current: LinkplayChannel, onPick: (LinkplayChannel) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkplayChannel.entries.forEach { ch ->
                FilterChip(selected = current == ch, onClick = { if (current != ch) onPick(ch) }, label = { Text(ch.label) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(label: String, options: List<LinkplayDevice>, selected: String?, onPick: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { d -> FilterChip(selected = selected == d.uuid, onClick = { onPick(d.uuid) }, label = { Text(d.name) }) }
        }
    }
}
