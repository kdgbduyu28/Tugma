package com.timborja.tugma.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.MediaPosition
import com.timborja.tugma.core.MediaTime
import com.timborja.tugma.core.SeekablePlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Track position for any brand whose playback can seek (UPnP/Sonos, Linkplay, Cast). */
class SeekViewModel(private val c: AppContainer, private val brand: Brand, private val id: String) : ViewModel() {
    var position by mutableStateOf<MediaPosition?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private val seekable: SeekablePlayback? get() = c.source(brand)?.playback(id) as? SeekablePlayback
    val supported: Boolean get() = seekable != null

    suspend fun read() {
        seekable?.let { s -> runCatching { s.position() }.onSuccess { position = it } }
    }

    fun seek(seconds: Int) {
        val s = seekable ?: return
        viewModelScope.launch {
            runCatching { s.seek(seconds) }.onFailure { message = "Seek: ${it.message}" }
            read()
        }
    }
}

/** A seek bar under Now playing, shown only for tracks that have a length (not radio). */
@Composable
fun SeekSection(brand: Brand, id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "seek-$brand-$id") { SeekViewModel(it, brand, id) }
    if (!vm.supported) return
    // Keeps the bar roughly current while the screen is open; the speakers don't push position.
    LaunchedEffect(id) { while (true) { vm.read(); delay(10_000) } }
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }

    val pos = vm.position ?: return
    if (!pos.seekable) return
    val length = pos.durationSeconds!!
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: (pos.elapsedSeconds ?: 0).toFloat()
    Slider(
        value = shown.coerceIn(0f, length.toFloat()),
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { vm.seek(it.toInt()) }; dragging = null },
        valueRange = 0f..length.toFloat(),
        modifier = Modifier.semantics { contentDescription = "Position in track" },
    )
    Text("${MediaTime.clock(shown.toInt())} / ${MediaTime.clock(length)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
