package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.RoomGroup
import com.timborja.tugma.sonos.SonosGroupPlanner
import com.timborja.tugma.sonos.SonosGroupRules
import com.timborja.tugma.sonos.SonosSpeaker
import com.timborja.tugma.sonos.blocked
import com.timborja.tugma.sonos.roomGroups
import com.timborja.tugma.sonos.warningTexts
import kotlinx.coroutines.launch

class SonosGroupsViewModel(c: AppContainer) : PlanViewModel(c) {
    var coordinator by mutableStateOf<SonosSpeaker?>(null)
    val selected = mutableStateListOf<String>()

    /** Group volume per coordinator uuid; null while unknown. */
    val volumes = mutableStateMapOf<String, Int>()
    val muted = mutableStateMapOf<String, Boolean>()
    val dragging = mutableStateMapOf<String, Float>()

    init {
        if (c.sonos.state.value.household == null) refresh()
    }

    fun planJoin(h: HouseholdState) {
        val coord = coordinator ?: return
        val members = selected.mapNotNull(h::speaker)
        val issues = SonosGroupRules.check(coord, members, h)
        if (issues.blocked) { message = issues.first { it.severity == com.timborja.tugma.sonos.Severity.BLOCK }.message; return }
        pending = SonosGroupPlanner.join(coord, members, h, issues.warningTexts)
    }

    fun planLeave(s: SonosSpeaker) { pending = SonosGroupPlanner.leave(listOf(s)) }
    fun planUngroup(g: RoomGroup) { pending = SonosGroupPlanner.ungroup(g) }
    fun planEverything(coord: SonosSpeaker, h: HouseholdState) { pending = SonosGroupPlanner.everything(coord, h) }

    override fun onExecuted() {
        selected.clear()
        volumes.clear()
    }

    suspend fun readVolumes(groups: List<RoomGroup>) {
        for (g in groups.filter { it.members.isNotEmpty() }) {
            val gv = c.sonos.groupVolume(g.coordinator.uuid) ?: continue
            runCatching { gv.volume() }.getOrNull()?.let { volumes[g.coordinator.uuid] = it }
            runCatching { gv.muted() }.getOrNull()?.let { muted[g.coordinator.uuid] = it }
        }
    }

    private var liveJob: kotlinx.coroutines.Job? = null
    private var watching: Map<String, String> = emptyMap()

    /** Follows GroupVolume/GroupMute events (sent as plain properties, not LastChange) so other apps' changes show up. */
    fun watchGroupVolumes() {
        val urls = c.sonos.groupVolumeEventUrls()
        if (urls == watching && liveJob?.isActive == true) return
        liveJob?.cancel()
        watching = urls
        if (urls.isEmpty()) return
        liveJob = viewModelScope.launch {
            val events = c.events.events() ?: return@launch
            val bySid = java.util.concurrent.ConcurrentHashMap<String, String>()
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                events.collect { e ->
                    val coord = bySid[e.sid] ?: return@collect
                    if (coord in dragging) return@collect
                    e.properties["GroupVolume"]?.trim()?.toIntOrNull()?.let { volumes[coord] = it }
                    e.properties["GroupMute"]?.trim()?.let { muted[coord] = it == "1" }
                }
            }
            var subs = urls.mapNotNull { (coord, url) -> c.events.subscribe(url)?.also { bySid[it.sid] = coord } }
            try {
                while (subs.isNotEmpty()) {
                    kotlinx.coroutines.delay((subs.minOf { it.timeoutSeconds } - 60).coerceAtLeast(30) * 1000L)
                    subs = subs.mapNotNull { c.events.renew(it) }
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { subs.forEach { c.events.unsubscribe(it) } }
            }
        }
    }

    fun setVolume(coord: String) {
        val v = dragging.remove(coord)?.toInt() ?: return
        volumes[coord] = v
        viewModelScope.launch { runCatching { c.sonos.groupVolume(coord)?.setVolume(v) }.onFailure { message = "Group volume failed: ${it.message}" } }
    }

    fun toggleMute(coord: String) {
        val m = !(muted[coord] ?: false)
        muted[coord] = m
        viewModelScope.launch { runCatching { c.sonos.groupVolume(coord)?.setMuted(m) }.onFailure { message = "Mute failed: ${it.message}" } }
    }
}

@Composable
fun SonosGroupsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { SonosGroupsViewModel(it) }
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    val h = ui.household
    val groups = h?.roomGroups().orEmpty()

    LaunchedEffect(groups.map { it.id to it.members.size }) {
        vm.readVolumes(groups)
        vm.watchGroupVolumes()
    }

    ScreenScaffold("Sonos groups", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Grouped rooms play the same thing in sync. Unlike stereo pairs, groups are easy to undo and don't change any bonds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (h == null) {
                Text(if (ui.loading) "Looking for speakers…" else "No Sonos household loaded yet.")
                return@Column
            }

            groups.filter { it.members.isNotEmpty() }.forEach { g ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium)
                        val coord = g.coordinator.uuid
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isMuted = vm.muted[coord] == true
                            IconButton(onClick = { vm.toggleMute(coord) }) {
                                Icon(if (isMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (isMuted) "Unmute group" else "Mute group")
                            }
                            val v = vm.dragging[coord] ?: vm.volumes[coord]?.toFloat()
                            Slider(
                                value = v ?: 0f,
                                onValueChange = { vm.dragging[coord] = it },
                                onValueChangeFinished = { vm.setVolume(coord) },
                                valueRange = 0f..100f,
                                enabled = v != null,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "${g.name} group volume" },
                            )
                            Text(v?.toInt()?.toString() ?: "–", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                        }
                        g.all.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.roomName + if (s.uuid == coord) " (leads)" else "", style = MaterialTheme.typography.bodyLarge)
                                    Text(s.modelName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { vm.planLeave(s) }, enabled = !vm.busy) { Text("Remove") }
                            }
                        }
                        OutlinedButton(onClick = { vm.planUngroup(g) }, enabled = !vm.busy) { Text("Ungroup all") }
                    }
                }
            }

            SectionLabel("Group rooms")
            val rooms = groups.flatMap { it.all }.sortedBy { it.roomName.lowercase() }
            SpeakerPicker("Play along with", vm.coordinator, rooms, onSelect = { vm.coordinator = it; it?.let { c -> vm.selected.remove(c.uuid) } })
            rooms.filter { it.uuid != vm.coordinator?.uuid }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.uuid in vm.selected, onCheckedChange = { on -> if (on) vm.selected.add(s.uuid) else vm.selected.remove(s.uuid) })
                    val inGroup = h.coordinatorOf(s.uuid)?.uuid
                    Text(
                        s.roomName + (inGroup?.takeIf { it != s.uuid }?.let { " · with ${h.nameOf(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val coord = vm.coordinator
            if (coord != null) {
                val issues = SonosGroupRules.check(coord, vm.selected.mapNotNull(h::speaker), h)
                if (vm.selected.isNotEmpty()) IssueList(issues)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.planJoin(h) }, enabled = !vm.busy && !issues.blocked) { Text("Review group") }
                    OutlinedButton(onClick = { vm.planEverything(coord, h) }, enabled = !vm.busy && rooms.size > 1) { Text("Every room") }
                }
            }
        }
    }

    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}
