package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.SceneFavorite
import com.timborja.tugma.sonos.ScenePlanner
import com.timborja.tugma.sonos.SonosFavorite
import com.timborja.tugma.sonos.SonosGroupRules
import com.timborja.tugma.sonos.SonosScene
import com.timborja.tugma.sonos.SonosSpeaker
import kotlinx.coroutines.launch
import java.util.UUID

class ScenesViewModel(c: AppContainer) : PlanViewModel(c) {
    val scenes = c.scenes.scenes
    var editing by mutableStateOf<SonosScene?>(null)
    var favorites by mutableStateOf<List<SonosFavorite>>(emptyList())
        private set

    init {
        if (c.sonos.state.value.household == null) refresh()
        loadFavorites()
    }

    fun loadFavorites() {
        val f = c.sonos.favorites() ?: return
        viewModelScope.launch { runCatching { f.list() }.onSuccess { favorites = it } }
    }

    fun apply(scene: SonosScene, h: HouseholdState) {
        ScenePlanner.problem(scene, h)?.let { message = it; return }
        pending = ScenePlanner.plan(scene, h) ?: run { message = "Nothing to change: ${scene.name} is already set up."; null }
    }

    fun newScene(h: HouseholdState): SonosScene {
        val first = h.speakers.firstOrNull(SonosGroupRules::canGroup)
        return SonosScene(UUID.randomUUID().toString(), "", first?.uuid.orEmpty())
    }

    fun save(scene: SonosScene) { c.scenes.save(scene); editing = null; message = "Saved ${scene.name}" }
    fun delete(scene: SonosScene) = c.scenes.delete(scene.id)
}

@Composable
fun ScenesScreen(onBack: () -> Unit) {
    val vm = containerViewModel { ScenesViewModel(it) }
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    val scenes by vm.scenes.collectAsState()
    val h = ui.household
    LaunchedEffect(h?.fetchedAtMillis) { if (vm.favorites.isEmpty()) vm.loadFavorites() }

    ScreenScaffold("Sonos scenes", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A scene sets which rooms play together, their volumes and, optionally, what starts playing. You review the exact requests before it runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (h == null) {
                Text(if (ui.loading) "Looking for speakers…" else "No Sonos household loaded yet.")
                return@Column
            }
            if (scenes.isEmpty()) Text("No scenes yet.")
            scenes.sortedBy { it.name.lowercase() }.forEach { s ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.rooms.joinToString(", ") { h.nameOf(it) } + (s.favorite?.let { " · ${it.title}" } ?: "") +
                                (s.reminder?.let { " · reminder ${it.label}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ScenePlanner.problem(s, h)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.apply(s, h) }, enabled = !vm.busy) { Text("Apply") }
                            TextButton(onClick = { vm.editing = s }) { Text("Edit") }
                            TextButton(onClick = { vm.delete(s) }) { Text("Delete") }
                        }
                    }
                }
            }
            FilledTonalButton(onClick = { vm.editing = vm.newScene(h) }) { Text("New scene") }
        }
    }

    val editing = vm.editing
    if (editing != null && h != null) SceneEditor(editing, h, vm.favorites, onSave = vm::save, onDismiss = { vm.editing = null })
    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SceneEditor(initial: SonosScene, h: HouseholdState, favorites: List<SonosFavorite>, onSave: (SonosScene) -> Unit, onDismiss: () -> Unit) {
    val rooms = h.speakers.filter(SonosGroupRules::canGroup).sortedBy { it.roomName.lowercase() }
    var name by remember { mutableStateOf(initial.name) }
    var leader by remember { mutableStateOf<SonosSpeaker?>(h.speaker(initial.leaderUuid) ?: rooms.firstOrNull()) }
    val members = remember { mutableStateMapOf<String, Boolean>().apply { initial.memberUuids.forEach { put(it, true) } } }
    val volumes = remember { mutableStateMapOf<String, Float>().apply { initial.volumes.forEach { (k, v) -> put(k, v.toFloat()) } } }
    var favorite by remember { mutableStateOf(initial.favorite) }
    var remind by remember { mutableStateOf(initial.reminder != null) }
    val reminderTime = androidx.compose.material3.rememberTimePickerState(
        (initial.reminder?.minuteOfDay ?: 420) / 60, (initial.reminder?.minuteOfDay ?: 420) % 60, is24Hour = true,
    )
    var reminderDays by remember { mutableStateOf(initial.reminder?.days ?: (1..5).toSet()) }
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty()) "New scene" else "Edit scene") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                SpeakerPicker("Main room (plays the music)", leader, rooms, onSelect = { leader = it; it?.let { l -> members.remove(l.uuid) } })
                Text("Also play in", style = MaterialTheme.typography.labelLarge)
                rooms.filter { it.uuid != leader?.uuid }.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = members[r.uuid] == true, onCheckedChange = { members[r.uuid] = it })
                        Text(r.roomName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Volumes (leave unticked to keep as is)", style = MaterialTheme.typography.labelLarge)
                (listOfNotNull(leader) + rooms.filter { members[it.uuid] == true && it.uuid != leader?.uuid }).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.uuid in volumes, onCheckedChange = { on -> if (on) volumes[r.uuid] = 25f else volumes.remove(r.uuid) })
                        Text(r.roomName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
                        Slider(value = volumes[r.uuid] ?: 25f, onValueChange = { volumes[r.uuid] = it }, valueRange = 0f..100f, enabled = r.uuid in volumes, modifier = Modifier.weight(1f).semantics { contentDescription = "${r.roomName} scene volume" })
                        Text(volumes[r.uuid]?.toInt()?.toString() ?: "–", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
                    }
                }
                Text("Start playing", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = favorite == null, onClick = { favorite = null }, label = { Text("Nothing") })
                    favorites.forEach { f -> FilterChip(selected = favorite?.uri == f.uri, onClick = { favorite = SceneFavorite.of(f) }, label = { Text(f.title) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = remind, onCheckedChange = { remind = it })
                }
                if (remind) {
                    androidx.compose.material3.TimeInput(state = reminderTime)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..6).forEach { d ->
                            FilterChip(
                                selected = d in reminderDays,
                                onClick = { reminderDays = if (d in reminderDays) reminderDays - d else reminderDays + d },
                                label = { Text(com.timborja.tugma.sonos.Recurrence.dayName(d)) },
                            )
                        }
                    }
                    Text(
                        "A notification at this time offers to apply the scene. Nothing changes until you tap Apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && leader != null,
                onClick = {
                    // Ask before saving: saving closes this dialog and disposes the launcher.
                    if (remind && android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    val l = leader!!
                    val memberIds = members.filterValues { it }.keys.filter { it != l.uuid }
                    val included = (listOf(l.uuid) + memberIds).toSet()
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            leaderUuid = l.uuid,
                            memberUuids = memberIds,
                            volumes = volumes.filterKeys { it in included }.mapValues { it.value.toInt() },
                            favorite = favorite,
                            reminder = if (remind && reminderDays.isNotEmpty()) {
                                com.timborja.tugma.sonos.SceneReminder(reminderTime.hour * 60 + reminderTime.minute, reminderDays)
                            } else {
                                null
                            },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
