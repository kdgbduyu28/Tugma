package com.timborja.tugma.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.RestorePlanner
import com.timborja.tugma.sonos.TopologySnapshot
import java.text.DateFormat
import java.util.Date

class SnapshotsViewModel(c: AppContainer) : PlanViewModel(c) {
    val snapshots = c.snapshots.all

    fun delete(id: String) = c.snapshots.delete(id)

    fun planRestore(snapshot: TopologySnapshot) {
        val h = household.value.household ?: return
        val plan = RestorePlanner.plan(snapshot, h)
        if (plan == null) message = "Nothing to restore: bonds already match this snapshot."
        else pending = plan
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
fun SnapshotsScreen(onOpen: (String) -> Unit) {
    val vm = containerViewModel { SnapshotsViewModel(it) }
    val snapshots by vm.snapshots.collectAsState()
    ScreenScaffold("Snapshots") { padding ->
        if (snapshots.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No snapshots yet",
                    "Tugma saves your speaker setup automatically before every pair or home-theater change, so you can see what changed and undo it.",
                )
            }
            return@ScreenScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(snapshots, key = { it.id }) { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Before: ${s.reason}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatTime(s.createdAtMillis)} · ${s.speakers.size} players",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.delete(s.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete snapshot") }
                    }
                }
            }
        }
    }
}

@Composable
fun SnapshotDetailScreen(id: String, onBack: () -> Unit) {
    val vm = containerViewModel { SnapshotsViewModel(it) }
    val snapshots by vm.snapshots.collectAsState()
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    var showXml by remember { mutableStateOf(false) }
    val snapshot = snapshots.firstOrNull { it.id == id }
    LaunchedEffect(snapshot) { if (snapshot == null) onBack() }

    ScreenScaffold("Snapshot", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        if (snapshot == null) return@ScreenScaffold
        val h = ui.household
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Before: ${snapshot.reason}", style = MaterialTheme.typography.titleMedium)
            Text(formatTime(snapshot.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionLabel("Changed since then")
            if (h == null) {
                Text("Load your speakers to compare.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val diff = RestorePlanner.diff(snapshot, h)
                if (diff.isEmpty()) Text("Nothing: bonds match the current setup.", style = MaterialTheme.typography.bodyMedium)
                diff.forEach { d ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(d.room, style = MaterialTheme.typography.bodyLarge)
                        Text("then: ${d.before ?: "standalone"}", style = MaterialTheme.typography.bodySmall)
                        Text("now:  ${d.after ?: "standalone"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = { vm.planRestore(snapshot) }, enabled = diff.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Review undo…")
                }
            }

            SectionLabel("Setup at the time")
            snapshot.speakers.forEach { l ->
                Text("${l.room} · ${l.model ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                l.role?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = { showXml = true }) { Text("Show raw ZoneGroupState") }
        }
    }

    if (showXml && snapshot != null) {
        AlertDialog(
            onDismissRequest = { showXml = false },
            confirmButton = { TextButton(onClick = { showXml = false }) { Text("Close") } },
            title = { Text("ZoneGroupState") },
            text = { Box(Modifier.verticalScroll(rememberScrollState())) { MonoBlock(prettySoap(snapshot.rawZoneGroupState)) } },
        )
    }
    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}
