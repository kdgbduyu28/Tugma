package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.SpeakerSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

data class MixerRow(val brand: Brand, val speaker: SpeakerSummary) {
    val key: String get() = "${brand.name}|${speaker.id}"
}

/** Every speaker's volume on one screen. Everyday controls: no confirm step. */
class VolumeMixerViewModel(private val c: AppContainer) : ViewModel() {
    val volumes = mutableStateMapOf<String, Int>()
    val max = mutableStateMapOf<String, Int>()
    val muted = mutableStateMapOf<String, Boolean>()
    val dragging = mutableStateMapOf<String, Float>()
    val sources = c.sources
    val hidden = c.settings.hiddenSpeakers
    val nicknames = c.settings.nicknames

    fun rows(): List<MixerRow> {
        val hidden = c.settings.hiddenSpeakers.value
        val nick = c.settings.nicknames.value
        return c.sources.flatMap { src ->
            src.summaries.value.speakers
                .filter { !it.hidden && Capability.VOLUME in it.capabilities && "${src.brand.name}|${it.id}" !in hidden }
                .map { s -> MixerRow(src.brand, nick["${src.brand.name}|${s.id}"]?.let { s.copy(name = it) } ?: s) }
        }.sortedBy { it.speaker.name.lowercase() }
    }

    /** Reads every speaker's volume in parallel; ones that don't answer just stay disabled. */
    suspend fun load(rows: List<MixerRow>) {
        rows.map { r ->
            viewModelScope.async {
                val control = c.source(r.brand)?.playback(r.speaker.id) ?: return@async
                runCatching { control.status() }.getOrNull()?.let { st ->
                    st.volume?.let { volumes[r.key] = it }
                    max[r.key] = st.maxVolume
                    st.muted?.let { muted[r.key] = it }
                }
            }
        }.awaitAll()
    }

    fun commit(r: MixerRow) {
        val v = dragging.remove(r.key)?.toInt() ?: return
        volumes[r.key] = v
        val control = c.source(r.brand)?.playback(r.speaker.id) ?: return
        viewModelScope.launch { runCatching { control.setVolume(v) } }
    }

    fun toggleMute(r: MixerRow) {
        val m = !(muted[r.key] ?: false)
        muted[r.key] = m
        val control = c.source(r.brand)?.playback(r.speaker.id) ?: return
        viewModelScope.launch { runCatching { control.setMuted(m) }.onFailure { muted[r.key] = !m } }
    }
}

@Composable
fun VolumeMixerScreen(onBack: () -> Unit) {
    val vm = containerViewModel { VolumeMixerViewModel(it) }
    // Observe every brand's list and the hidden/nickname settings so rows update after a scan.
    val states = vm.sources.map { it.summaries.collectAsState().value }
    val hidden by vm.hidden.collectAsState()
    val nicknames by vm.nicknames.collectAsState()
    val rows = androidx.compose.runtime.remember(states, hidden, nicknames) { vm.rows() }
    LaunchedEffect(rows.map { it.key }) { vm.load(rows) }

    ScreenScaffold("Volume mixer", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rows.isEmpty()) Text("No speakers with volume control yet. Scan on the Speakers tab first.")
            rows.forEach { r ->
                val mx = vm.max[r.key] ?: 100
                val v = vm.dragging[r.key] ?: vm.volumes[r.key]?.toFloat()
                Text("${r.speaker.name} · ${r.brand.displayName}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val m = vm.muted[r.key] == true
                    IconButton(onClick = { vm.toggleMute(r) }, enabled = v != null) {
                        Icon(if (m) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (m) "Unmute ${r.speaker.name}" else "Mute ${r.speaker.name}")
                    }
                    Slider(
                        value = (v ?: 0f).coerceIn(0f, mx.toFloat()),
                        onValueChange = { vm.dragging[r.key] = it },
                        onValueChangeFinished = { vm.commit(r) },
                        valueRange = 0f..mx.toFloat(),
                        enabled = v != null,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "${r.speaker.name} volume" },
                    )
                    Text(v?.toInt()?.toString() ?: "–", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp).padding(start = 8.dp))
                }
            }
        }
    }
}
