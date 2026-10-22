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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.Recurrence
import com.timborja.tugma.sonos.SonosAlarm
import com.timborja.tugma.sonos.SonosFavorite
import com.timborja.tugma.sonos.SonosGroupRules
import com.timborja.tugma.sonos.SonosSpeaker
import kotlinx.coroutines.launch

class SonosAlarmsViewModel(private val c: AppContainer) : ViewModel() {
    val household = c.sonos.state
    var alarms by mutableStateOf<List<SonosAlarm>?>(null)
        private set
    var favorites by mutableStateOf<List<SonosFavorite>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
    var editing by mutableStateOf<SonosAlarm?>(null)
    var deleting by mutableStateOf<SonosAlarm?>(null)

    fun load() {
        val a = c.sonos.alarms() ?: return
        viewModelScope.launch {
            runCatching { a.list() }.onSuccess { alarms = it }.onFailure { message = "Couldn't read alarms: ${it.message}" }
            c.sonos.favorites()?.let { f -> runCatching { f.list() }.onSuccess { list -> favorites = list.filter { !it.needsQueue } } }
        }
    }

    private fun write(label: String, block: suspend com.timborja.tugma.sonos.SonosAlarms.() -> Unit) {
        val a = c.sonos.alarms() ?: return
        viewModelScope.launch {
            runCatching { a.block() }.onSuccess { message = label }.onFailure { message = it.message ?: "Failed" }
            load()
        }
    }

    fun save(alarm: SonosAlarm) {
        editing = null
        if (alarm.id == null) write("Alarm added for ${alarm.shortTime}") { create(alarm) } else write("Alarm updated") { update(alarm) }
    }

    fun toggle(alarm: SonosAlarm, on: Boolean) = write(if (on) "Alarm on" else "Alarm off") { update(alarm.copy(enabled = on)) }

    fun delete(alarm: SonosAlarm) {
        deleting = null
        val id = alarm.id ?: return
        write("Alarm deleted") { delete(id) }
    }
}

@Composable
fun SonosAlarmsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { SonosAlarmsViewModel(it) }
    val ui by vm.household.collectAsState()
    val rooms = ui.household?.speakers?.filter(SonosGroupRules::canGroup)?.sortedBy { it.roomName.lowercase() }.orEmpty()
    LaunchedEffect(ui.household?.fetchedAtMillis) { vm.load() }

    ScreenScaffold("Sonos alarms", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Alarms live on the speakers, so they ring even when your phone is off. They're shared with the Sonos app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val alarms = vm.alarms
            when {
                ui.household == null -> Text("No Sonos household loaded yet.")
                alarms == null -> Text("Reading alarms…")
                alarms.isEmpty() -> Text("No alarms yet.")
                else -> alarms.forEach { a ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.shortTime, style = MaterialTheme.typography.headlineSmall)
                                val room = ui.household?.nameOf(a.roomUuid) ?: a.roomUuid
                                val sound = if (a.isChime) "Chime" else vm.favorites.firstOrNull { it.uri == a.programUri }?.title ?: "Music"
                                Text("${Recurrence.label(a.recurrence)} · $room · $sound · vol ${a.volume}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { vm.editing = a }) { Text("Edit") }
                                    TextButton(onClick = { vm.deleting = a }) { Text("Delete") }
                                }
                            }
                            Switch(checked = a.enabled, onCheckedChange = { vm.toggle(a, it) })
                        }
                    }
                }
            }
            if (rooms.isNotEmpty()) {
                FloatingActionButton(onClick = { vm.editing = SonosAlarm(startTime = SonosAlarm.time(7, 0), roomUuid = rooms.first().uuid) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add alarm")
                }
            }
        }
    }

    vm.editing?.let { AlarmDialog(it, rooms, vm.favorites, onSave = vm::save, onDismiss = { vm.editing = null }) }
    vm.deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { vm.deleting = null },
            title = { Text("Delete the ${a.shortTime} alarm?") },
            text = { Text("It's removed from the speakers and the Sonos app too.") },
            confirmButton = { TextButton(onClick = { vm.delete(a) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { vm.deleting = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmDialog(
    initial: SonosAlarm,
    rooms: List<SonosSpeaker>,
    favorites: List<SonosFavorite>,
    onSave: (SonosAlarm) -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = initial.startTime.split(':').mapNotNull { it.toIntOrNull() }
    val time = rememberTimePickerState(parts.getOrElse(0) { 7 }, parts.getOrElse(1) { 0 }, is24Hour = true)
    var days by remember { mutableStateOf(Recurrence.days(initial.recurrence)) }
    var room by remember { mutableStateOf(rooms.firstOrNull { it.uuid == initial.roomUuid } ?: rooms.firstOrNull()) }
    var volume by remember { mutableFloatStateOf(initial.volume.toFloat()) }
    var program by remember { mutableStateOf(favorites.firstOrNull { it.uri == initial.programUri }) }
    var duration by remember { mutableStateOf(initial.durationOrBlank) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "New alarm" else "Edit alarm") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeInput(state = time)
                Text("Repeat", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { d ->
                        FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(Recurrence.dayName(d)) })
                    }
                }
                Text(Recurrence.label(Recurrence.of(days)), style = MaterialTheme.typography.bodySmall)
                SpeakerPicker("Room", room, rooms, onSelect = { room = it })
                Text("Volume ${volume.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, modifier = Modifier.semantics { contentDescription = "Alarm volume" })
                Text("Sound", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = program == null, onClick = { program = null }, label = { Text("Chime") })
                    favorites.forEach { f -> FilterChip(selected = program == f, onClick = { program = f }, label = { Text(f.title) }) }
                }
                if (favorites.isEmpty()) Text("Radio and stream favorites show up here as alarm sounds.", style = MaterialTheme.typography.bodySmall)
                Text("Plays for", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("00:15:00" to "15 min", "00:30:00" to "30 min", "01:00:00" to "1 hour", "" to "Until stopped").forEach { (v, label) ->
                        FilterChip(selected = duration == v, onClick = { duration = v }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = room != null,
                onClick = {
                    onSave(
                        initial.copy(
                            startTime = SonosAlarm.time(time.hour, time.minute),
                            recurrence = Recurrence.of(days),
                            roomUuid = room!!.uuid,
                            volume = volume.toInt(),
                            programUri = program?.uri ?: SonosAlarm.BUZZER,
                            programMetaData = program?.metadata.orEmpty(),
                            durationOrBlank = duration,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
