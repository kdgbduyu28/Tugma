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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.TransportState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SpeakerDetailViewModel(c: AppContainer, val brand: Brand, val id: String) : ViewModel() {
    private val source = c.source(brand)
    val state: StateFlow<SourceState> = source?.summaries ?: MutableStateFlow(SourceState())

    var status by mutableStateOf<PlaybackStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    /** Slider position while the user drags, so polling doesn't fight the thumb. */
    var dragVolume by mutableStateOf<Float?>(null)

    private fun control(): PlaybackControl? = source?.playback(id)

    suspend fun poll() {
        val control = control() ?: return
        runCatching { control.status() }
            .onSuccess { status = it; error = null }
            .onFailure { error = it.message ?: "Couldn't reach the speaker" }
    }

    fun act(label: String, block: suspend PlaybackControl.() -> Unit) {
        val control = control() ?: return
        viewModelScope.launch {
            runCatching { control.block() }.onFailure { message = "$label failed: ${it.message}" }
            poll()
        }
    }

    fun commitVolume() {
        val v = dragVolume ?: return
        act("Volume") { setVolume(v.toInt()) }
        dragVolume = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerDetailScreen(
    brand: Brand,
    id: String,
    onBack: () -> Unit,
    onPair: (String) -> Unit,
    onHomeTheater: (String) -> Unit,
) {
    val vm = containerViewModel(key = "detail-$brand-$id") { SpeakerDetailViewModel(it, brand, id) }
    val state by vm.state.collectAsState()
    val speaker = state.speakers.firstOrNull { it.id == id }

    // Status is polled while the screen is open; slowly, because every poll shows up in the debug log.
    LaunchedEffect(id) {
        while (true) {
            vm.poll()
            delay(10_000)
        }
    }

    ScreenScaffold(
        title = speaker?.name ?: "Speaker",
        onBack = onBack,
        message = vm.message,
        onMessageShown = { vm.message = null },
        actions = { IconButton(onClick = { vm.act("Refresh") {} }) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh status") } },
    ) { padding ->
        if (speaker == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This speaker isn't in the latest scan. Go back and refresh.")
            }
            return@ScreenScaffold
        }
        val caps = speaker.capabilities
        val status = vm.status
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Badge(speaker.brand.displayName, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        speaker.manufacturer?.takeIf { it != speaker.brand.displayName }?.let { Badge(it) }
                        if (speaker.model.isNotBlank()) Badge(speaker.model)
                    }
                    Text("${speaker.host}:${speaker.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(speaker.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    speaker.roleLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("Supports: " + capabilityLabels(caps).joinToString(", ").ifEmpty { "nothing Tugma can control yet" }, style = MaterialTheme.typography.bodySmall)
                }
            }

            vm.error?.let { IssueList(listOf(com.timborja.tugma.sonos.Issue(com.timborja.tugma.sonos.Severity.WARN, "Couldn't read status: $it"))) }

            if (Capability.TRANSPORT in caps) {
                SectionLabel("Now playing")
                Text(status?.title ?: transportLabel(status?.transport), style = MaterialTheme.typography.titleMedium)
                status?.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (status?.title != null) Text(transportLabel(status.transport), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.act("Previous") { previous() } }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous") }
                    val playing = status?.transport == TransportState.PLAYING
                    FilledIconButton(onClick = { vm.act(if (playing) "Pause" else "Play") { if (playing) pause() else play() } }, modifier = Modifier.size(56.dp)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { vm.act("Stop") { stop() } }) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
                    IconButton(onClick = { vm.act("Next") { next() } }) { Icon(Icons.Filled.SkipNext, contentDescription = "Next") }
                }
                if (speaker.brand == Brand.SONOS) {
                    Text(
                        "Playback controls the whole group this speaker belongs to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (Capability.VOLUME in caps) {
                SectionLabel("Volume")
                val max = status?.maxVolume ?: 100
                val current = vm.dragVolume ?: status?.volume?.toFloat() ?: 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (Capability.MUTE in caps) {
                        val muted = status?.muted == true
                        IconButton(onClick = { vm.act("Mute") { setMuted(!muted) } }) {
                            Icon(
                                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = if (muted) "Unmute" else "Mute",
                            )
                        }
                    }
                    Slider(
                        value = current.coerceIn(0f, max.toFloat()),
                        onValueChange = { vm.dragVolume = it },
                        onValueChangeFinished = vm::commitVolume,
                        valueRange = 0f..max.toFloat(),
                        enabled = status?.volume != null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        status?.volume?.let { "${current.toInt()}" } ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(40.dp).padding(start = 8.dp),
                    )
                }
                if (max != 100) {
                    Text("This speaker's volume runs 0–$max.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (speaker.brand == Brand.SONOS && (Capability.STEREO_PAIR in caps || Capability.HT_PRIMARY in caps)) {
                SectionLabel("Bonding")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Capability.STEREO_PAIR in caps) OutlinedButton(onClick = { onPair(id) }) { Text("Stereo pair…") }
                    if (Capability.HT_PRIMARY in caps) OutlinedButton(onClick = { onHomeTheater(id) }) { Text("Home theater…") }
                }
            }
        }
    }
}

private fun transportLabel(t: TransportState?): String = when (t) {
    TransportState.PLAYING -> "Playing"
    TransportState.PAUSED -> "Paused"
    TransportState.STOPPED -> "Stopped"
    TransportState.NO_MEDIA -> "Nothing queued"
    TransportState.TRANSITIONING -> "Loading…"
    TransportState.UNKNOWN, null -> "Status unknown"
}
