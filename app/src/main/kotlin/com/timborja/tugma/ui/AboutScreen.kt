package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.timborja.tugma.AppContainer
import com.timborja.tugma.BuildConfig
import com.timborja.tugma.data.Backup
import com.timborja.tugma.data.BackupCodec
import kotlinx.coroutines.launch

class AboutViewModel(private val c: AppContainer) : ViewModel() {
    val demo = c.settings.demoMode
    val fronts = c.settings.experimentalFronts
    val manualHosts = c.settings.manualHosts

    fun setDemo(on: Boolean) = c.setDemoMode(on)
    val theme = c.settings.theme
    val wallpaperColors = c.settings.wallpaperColors
    fun setTheme(m: ThemeMode) = c.settings.setTheme(m)
    fun setWallpaperColors(on: Boolean) = c.settings.setWallpaperColors(on)

    fun setFronts(on: Boolean) = c.settings.setExperimentalFronts(on)
    fun resetDemo() = c.resetDemo()
    fun removeHost(h: String) = c.settings.removeManualHost(h)

    var message by mutableStateOf<String?>(null)
    var pendingRestore by mutableStateOf<Backup?>(null)

    fun backupText(): String = BackupCodec.encode(
        Backup(c.eq.library.value, c.stations.stations.value, c.scenes.scenes.value, c.settings.manualHosts.value, c.settings.hiddenSpeakers.value, c.settings.nicknames.value),
        System.currentTimeMillis(),
    )

    fun read(text: String) {
        runCatching { BackupCodec.decode(text) }.onSuccess { pendingRestore = it }.onFailure { message = it.message ?: "Couldn't read that file" }
    }

    fun restore(b: Backup) {
        pendingRestore = null
        b.eq?.let(c.eq::replaceLibrary)
        b.stations?.let(c.stations::replaceAll)
        b.scenes?.let(c.scenes::replaceAll)
        b.manualHosts?.let(c.settings::replaceManualHosts)
        b.hiddenSpeakers?.let(c.settings::replaceHidden)
        b.nicknames?.let(c.settings::replaceNicknames)
        message = "Restored ${b.summary}"
    }
}

@Composable
fun AboutScreen(onDiagnostics: () -> Unit) {
    val vm = containerViewModel { AboutViewModel(it) }
    val demo by vm.demo.collectAsState()
    val fronts by vm.fronts.collectAsState()
    val hosts by vm.manualHosts.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val text = vm.backupText()
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Couldn't open the file") } }
                .onSuccess { vm.message = "Backup saved" }.onFailure { vm.message = it.message ?: "Couldn't save" }
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(BackupCodec.MAX_BYTES + 1)
                        String(bytes, Charsets.UTF_8)
                    } ?: error("Couldn't open the file")
                }
            }.onSuccess(vm::read).onFailure { vm.message = it.message ?: "Couldn't read" }
        }
    }

    ScreenScaffold("About", message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tugma ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tugma (\"match\") pairs speakers the official apps won't: stereo pairs of different models, and any speaker as a soundbar's surrounds or sub.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "Unofficial. Tugma is not affiliated with, endorsed by or supported by Sonos, Inc. or any other speaker maker. " +
                        "It sends the same local-network commands the official apps use, in combinations those apps don't allow. " +
                        "A firmware update could stop it working at any time. Use at your own risk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            SectionLabel("Not finding your speakers?")
            OutlinedButton(onClick = onDiagnostics) { Text("Run diagnostics") }

            SectionLabel("Settings")
            val theme by vm.theme.collectAsState()
            val wallpaper by vm.wallpaperColors.collectAsState()
            Text("Appearance", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    androidx.compose.material3.FilterChip(selected = theme == m, onClick = { vm.setTheme(m) }, label = { Text(m.label) })
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                SettingRow("Wallpaper colours", "Use your wallpaper's colours (Material You) instead of Tugma's green.", wallpaper, vm::setWallpaperColors)
            }
            SettingRow("Demo household", "Fake speakers to try every screen safely. Nothing real changes.", demo, vm::setDemo)
            if (demo) OutlinedButton(onClick = vm::resetDemo) { Text("Reset demo household") }
            SettingRow(
                "Experimental fronts",
                "Show front-left/right satellite slots. No reference documents these; the soundbar may refuse them.",
                fronts,
                vm::setFronts,
            )
            if (hosts.isNotEmpty()) {
                SectionLabel("Speakers added by IP")
                hosts.sorted().forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.removeHost(h) }) { Text("Forget") }
                    }
                }
            }

            SectionLabel("Backup")
            Text(
                "Save your EQ profiles, saved links, Sonos scenes and speakers added by IP to a file, or restore them after a reinstall. Restoring replaces what's in the backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("tugma-backup.json") }) { Text("Save backup…") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restore…") }
            }

            SectionLabel("Licence and credits")
            Text(
                "Tugma is free software under the GNU GPL v3 or later.\n\n" +
                    "Protocol formats were checked against SoCo (github.com/SoCo/SoCo, MIT licence) and " +
                    "pair-sonos by Erik Månsson (github.com/erikmansson/pair-sonos, MIT licence). " +
                    "Test fixtures include sample topology payloads from SoCo's test suite.\n\n" +
                    "Soundcore support is ported from OpenSCQ30 by Kyle Scheuing and contributors " +
                    "(github.com/Oppzippy/OpenSCQ30, GPL-3.0), including its packet test vectors.\n\n" +
                    "Redmi Buds support is ported from Gadgetbridge (codeberg.org/Freeyourgadget/Gadgetbridge), " +
                    "originally by Jonathan Gobbo and Gadgetbridge contributors. Those files stay under AGPL-3.0-or-later.\n\n" +
                    "Sonos is a trademark of Sonos, Inc., used here only to say which speakers Tugma works with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    vm.pendingRestore?.let { b -> RestoreDialog(b, onRestore = { vm.restore(b) }, onDismiss = { vm.pendingRestore = null }) }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun RestoreDialog(backup: Backup, onRestore: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = { Text("This replaces ${backup.summary} on this phone. Anything else is left as it is.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onRestore) { Text("Restore") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
