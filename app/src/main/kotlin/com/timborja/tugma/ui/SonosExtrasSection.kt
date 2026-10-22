package com.timborja.tugma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.timborja.tugma.sonos.QueueItem
import com.timborja.tugma.sonos.SonosFavorite
import com.timborja.tugma.sonos.SonosQueue
import com.timborja.tugma.sonos.SonosSpeaker
import kotlinx.coroutines.launch

/** Favorites and the sleep timer for one room. Playback-class actions: no confirm step. */
class SonosExtrasViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    var favorites by mutableStateOf<List<SonosFavorite>?>(null)
        private set
    var sleepSeconds by mutableStateOf<Int?>(null)
        private set
    var queue by mutableStateOf<List<QueueItem>?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    val speaker: SonosSpeaker? get() = c.sonos.state.value.household?.speaker(id)
    val lineInSources: List<SonosSpeaker> get() = c.sonos.state.value.household?.speakers?.filter { it.hasLineIn && !it.invisible }.orEmpty()

    fun load() {
        viewModelScope.launch {
            c.sonos.favorites()?.let { f -> runCatching { f.list() }.onSuccess { favorites = it }.onFailure { message = "Favorites: ${it.message}" } }
            readTimer()
            readQueue()
        }
    }

    private suspend fun readQueue() {
        c.sonos.queue(id)?.let { q -> runCatching { q.list() }.onSuccess { queue = it } }
    }

    private fun queueDo(done: String?, block: suspend SonosQueue.() -> Unit) {
        val q = c.sonos.queue(id) ?: return
        viewModelScope.launch {
            runCatching { q.block() }.onSuccess { done?.let { message = it } }.onFailure { message = "Queue: ${it.message}" }
            readQueue()
        }
    }

    fun playFrom(item: QueueItem) = queueDo("Playing ${item.title}") { playFrom(item.position) }
    fun removeFromQueue(item: QueueItem) = queueDo(null) { remove(item.position) }
    fun clearQueue() = queueDo("Queue cleared") { clear() }

    fun lineIn(source: SonosSpeaker) {
        val inputs = c.sonos.inputs(id) ?: return
        viewModelScope.launch { runCatching { inputs.lineIn(source) }.onSuccess { message = "Playing ${source.roomName} line-in" }.onFailure { message = "Line-in: ${it.message}" } }
    }

    fun tv() {
        val bar = speaker ?: return
        val inputs = c.sonos.inputs(id) ?: return
        viewModelScope.launch { runCatching { inputs.tv(bar) }.onSuccess { message = "Switched to TV" }.onFailure { message = "TV: ${it.message}" } }
    }

    private suspend fun readTimer() {
        c.sonos.sleepTimer(id)?.let { t -> runCatching { t.remainingSeconds() }.onSuccess { sleepSeconds = it } }
    }

    fun play(f: SonosFavorite) {
        val player = c.sonos.player(id) ?: return
        viewModelScope.launch {
            runCatching { player.play(f) }
                .onSuccess { message = "Playing ${f.title}"; readQueue() }
                .onFailure { message = "Couldn't play ${f.title}: ${it.message}" }
        }
    }

    fun sleep(minutes: Int?) {
        val t = c.sonos.sleepTimer(id) ?: return
        viewModelScope.launch {
            runCatching { t.set(minutes) }.onFailure { message = "Sleep timer: ${it.message}" }
            readTimer()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SonosExtrasSection(id: String, onAlarms: () -> Unit, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "extras-$id") { SonosExtrasViewModel(it, id) }
    LaunchedEffect(id) { vm.load() }
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }

    SectionLabel("Favorites")
    val favs = vm.favorites
    when {
        favs == null -> Text("Reading My Sonos…", style = MaterialTheme.typography.bodySmall)
        favs.isEmpty() -> Text("No favorites yet. Add some in the Sonos app; they show up here.", style = MaterialTheme.typography.bodySmall)
        else -> Column {
            favs.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.play(f) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${f.title}")
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = MaterialTheme.typography.bodyLarge)
                        val note = listOfNotNull(f.description, if (f.needsQueue) "replaces the queue" else null).joinToString(" · ")
                        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    val q = vm.queue
    if (!q.isNullOrEmpty()) {
        SectionLabel("Queue · ${q.size}")
        Column {
            q.take(50).forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { vm.playFrom(item) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.position}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium)
                        listOfNotNull(item.artist, item.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { vm.removeFromQueue(item) }) { Text("Remove") }
                }
            }
            if (q.size > 50) Text("…and ${q.size - 50} more", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = vm::clearQueue) { Text("Clear queue") }
    }

    val sources = vm.lineInSources
    val isBar = vm.speaker?.isSoundbar == true
    if (sources.isNotEmpty() || isBar) {
        SectionLabel("Inputs")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isBar) AssistChip(onClick = vm::tv, label = { Text("TV") })
            sources.forEach { s -> AssistChip(onClick = { vm.lineIn(s) }, label = { Text("Line-in: ${s.roomName}") }) }
        }
    }

    SectionLabel("Sleep timer")
    val left = vm.sleepSeconds
    Text(
        if (left == null) "Off" else "Stops in ${left / 3600}h ${left / 60 % 60}m (whole group)",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 45, 60, 90).forEach { m -> AssistChip(onClick = { vm.sleep(m) }, label = { Text(if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) " ${m % 60}" else ""}") }) }
        if (left != null) TextButton(onClick = { vm.sleep(null) }) { Text("Cancel timer") }
    }

    SectionLabel("Alarms")
    OutlinedButton(onClick = onAlarms) { Text("Alarms…") }
}
