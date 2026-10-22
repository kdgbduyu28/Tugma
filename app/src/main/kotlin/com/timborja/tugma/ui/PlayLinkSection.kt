package com.timborja.tugma.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Station
import com.timborja.tugma.core.UrlPlayback
import kotlinx.coroutines.launch

class PlayLinkViewModel(private val c: AppContainer, private val brand: Brand, private val id: String) : ViewModel() {
    val stations = c.stations.stations
    var message by mutableStateOf<String?>(null)
    var adding by mutableStateOf(false)

    val supported: Boolean get() = c.source(brand)?.playback(id) is UrlPlayback

    fun play(s: Station) {
        val p = c.source(brand)?.playback(id) as? UrlPlayback ?: return
        viewModelScope.launch {
            runCatching { p.playUrl(s.url, s.name, s.live) }
                .onSuccess { message = "Playing ${s.name}" }
                .onFailure { message = "Couldn't play ${s.name}: ${it.message}" }
        }
    }

    fun save(s: Station) { c.stations.add(s); adding = false }
    fun remove(s: Station) = c.stations.remove(s)
}

/** Internet radio or any audio link, on any brand that can take one. Everyday control: no confirm step. */
@Composable
fun PlayLinkSection(brand: Brand, id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "link-$brand-$id") { PlayLinkViewModel(it, brand, id) }
    if (!vm.supported) return
    val stations by vm.stations.collectAsState()
    androidx.compose.runtime.LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }

    SectionLabel("Play a link")
    if (stations.isEmpty()) {
        Text("Save internet radio or audio links here; they work on every brand that supports it.", style = MaterialTheme.typography.bodySmall)
    }
    Column {
        stations.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { vm.play(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${s.name}")
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = { vm.remove(s) }) { Text("Remove") }
            }
        }
    }
    OutlinedButton(onClick = { vm.adding = true }) { Text("Add link…") }

    if (vm.adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var live by remember { mutableStateOf(true) }
        val station = Station(name.trim().ifBlank { url.trim().substringAfter("://").substringBefore('/') }, url.trim(), live)
        AlertDialog(
            onDismissRequest = { vm.adding = false },
            title = { Text("Add a link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL (http or https)") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live radio stream", modifier = Modifier.weight(1f))
                        Switch(checked = live, onCheckedChange = { live = it })
                    }
                    if (url.isNotBlank() && !station.isValidUrl) Text("That doesn't look like an http(s) link.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.play(station) }, enabled = station.isValidUrl) { Text("Play now") }
                    TextButton(onClick = { vm.save(station) }, enabled = station.isValidUrl) { Text("Save") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.adding = false }) { Text("Cancel") } },
        )
    }
}
