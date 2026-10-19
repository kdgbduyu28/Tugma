package com.timborja.tugma.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.LogEntry
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugViewModel(private val c: AppContainer) : ViewModel() {
    val entries = c.log.entries
    fun clear() = c.log.clear()
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun LogEntry.actionName(): String =
    requestHeaders.entries.firstOrNull { it.key.equals("SOAPACTION", true) }?.value?.trim('"')?.substringAfter('#')
        ?: runCatching { URI(url).path }.getOrDefault(url)

@Composable
fun DebugScreen() {
    val vm = containerViewModel { DebugViewModel(it) }
    val entries by vm.entries.collectAsState()
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var writesOnly by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val shown = entries.filter { (!errorsOnly || it.isError) && (!writesOnly || (it.method == "POST" && !it.actionName().startsWith("Get"))) }

    ScreenScaffold(
        "Debug log",
        actions = {
            IconButton(
                onClick = {
                    val text = shown.reversed().joinToString("\n") { it.toText() }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share log"))
                },
                enabled = shown.isNotEmpty(),
            ) { Icon(Icons.Outlined.Share, contentDescription = "Share log") }
            IconButton(onClick = vm::clear, enabled = entries.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear log") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = errorsOnly, onClick = { errorsOnly = !errorsOnly }, label = { Text("Errors") })
                FilterChip(selected = writesOnly, onClick = { writesOnly = !writesOnly }, label = { Text("Writes") })
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState("Nothing logged yet", "Every request Tugma sends to a speaker, and the reply, shows up here. Share it when reporting a problem.")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { LogCard(it) }
                }
            }
        }
    }
}

@Composable
private fun LogCard(e: LogEntry) {
    var open by remember { mutableStateOf(false) }
    val tone = if (e.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    Card(colors = CardDefaults.cardColors(containerColor = tone), modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.actionName(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(e.status?.toString() ?: "ERR", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "${timeFormat.format(Date(e.timestampMillis))} · ${e.durationMillis} ms · ${e.method} ${e.url}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            e.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (open) {
                if (e.requestBody.isNotEmpty()) {
                    SectionLabel("Request")
                    MonoBlock(e.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + prettySoap(e.requestBody))
                }
                e.responseBody?.let {
                    SectionLabel("Response")
                    MonoBlock(prettySoap(it).take(20_000))
                }
            }
        }
    }
}
