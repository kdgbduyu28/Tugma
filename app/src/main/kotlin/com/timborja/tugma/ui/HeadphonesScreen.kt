package com.timborja.tugma.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.data.BtAudioDevice
import com.timborja.tugma.data.RedmiBudsRepository
import com.timborja.tugma.eq.EqLibrary
import com.timborja.tugma.eq.EqMath
import com.timborja.tugma.eq.EqProfile
import com.timborja.tugma.eq.EqSource
import com.timborja.tugma.eq.OutputDevice
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.roundToInt

class HeadphonesViewModel(private val c: AppContainer) : ViewModel() {
    val bluetooth = c.bluetooth.state
    val demo = c.settings.demoMode
    fun isSoundcore(name: String) = c.soundcore.isLikelySoundcore(name)
    fun isRedmi(name: String) = c.redmi.isLikely(name)
    val demoSoundcore = c.soundcore.demoCandidate
    val output = c.output.current
    val library = c.eq.library
    val enabled = c.eq.enabled
    val status = c.eq.status

    /** Profile shown in the editor; null follows the current output's profile. */
    var selectedId by mutableStateOf<String?>(null)
        private set
    /** Unsaved edits, previewed live through the running EQ. */
    var draft by mutableStateOf<EqProfile?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun shown(lib: EqLibrary, out: OutputDevice): EqProfile =
        draft ?: selectedId?.let(lib::profile) ?: lib.profileFor(out.key)

    fun refreshBluetooth() {
        viewModelScope.launch { c.bluetooth.refresh() }
    }

    fun listenForCodec(): () -> Unit = c.bluetooth.listenForCodecChanges()

    fun setEnabled(on: Boolean) = c.eq.setEnabled(on)

    fun select(id: String) {
        selectedId = id
        discard()
    }

    fun edit(base: EqProfile, change: (EqProfile) -> EqProfile) {
        draft = change(draft ?: base).clamped()
        c.eq.setPreview(draft)
    }

    fun discard() {
        draft = null
        c.eq.setPreview(null)
    }

    /** Saves edits in place for user profiles; presets and "save as" make a new profile. */
    fun save(asName: String? = null) {
        val d = draft ?: selectedId?.let(library.value::profile) ?: return
        val saved = if (asName != null || d.source == EqSource.PRESET) {
            d.copy(id = c.eq.newId(), name = asName?.trim()?.ifBlank { null } ?: "${d.name} (custom)", source = EqSource.USER, note = null)
        } else d
        c.eq.save(saved)
        selectedId = saved.id
        discard()
        message = "Saved ${saved.name}"
    }

    fun delete(p: EqProfile) {
        c.eq.delete(p.id)
        selectedId = null
        discard()
        message = "Deleted ${p.name}"
    }

    fun assign(deviceKey: String, profileId: String?, label: String) {
        c.eq.assign(deviceKey, profileId)
        message = "$label now uses ${profileId?.let(library.value::profile)?.name ?: "the default profile"}"
    }

    fun setDefault(p: EqProfile) {
        c.eq.setDefault(p.id)
        message = "${p.name} is now the default"
    }

    fun import(uri: Uri) {
        runCatching { c.eq.import(uri) }
            .onSuccess { r ->
                selectedId = r.profile.id
                discard()
                message = "Imported ${r.profile.name}" + if (r.warnings.isNotEmpty()) " (${r.warnings.size} warnings)" else ""
            }
            .onFailure { message = it.message ?: "Import failed" }
    }

    override fun onCleared() {
        c.eq.setPreview(null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeadphonesScreen(
    onSoundcore: (address: String, name: String) -> Unit,
    onRedmi: (address: String, name: String) -> Unit,
    onLab: () -> Unit,
) {
    val vm = containerViewModel { HeadphonesViewModel(it) }
    val demo by vm.demo.collectAsState()
    val bt by vm.bluetooth.collectAsState()
    val out by vm.output.collectAsState()
    val lib by vm.library.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val status by vm.status.collectAsState()
    val context = LocalContext.current
    var saveAs by remember { mutableStateOf(false) }

    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshBluetooth() }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setEnabled(true) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    LaunchedEffect(out.key) { vm.refreshBluetooth() }
    DisposableEffect(Unit) {
        val stop = vm.listenForCodec()
        onDispose { stop() }
    }

    val shown = vm.shown(lib, out)
    val editable = shown.bands.size == EqMath.GRAPHIC_10.size

    ScreenScaffold("Headphones", message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Current output and its profile.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Playing through", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(outputIcon(out.kind), contentDescription = null)
                        Text(out.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("EQ profile: ${lib.profileFor(out.key).name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        ProfileMenu("Change", lib, includeDefault = true) { id -> vm.assign(out.key, id, out.label) }
                    }
                }
            }

            // On/off and what the EQ is attached to.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                    Text(statusText(enabled, status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setEnabled(on)
                })
            }
            if (enabled && status.running && !status.globalAttached) {
                IssueList(
                    listOf(
                        com.timborja.tugma.sonos.Issue(
                            com.timborja.tugma.sonos.Severity.WARN,
                            "This phone doesn't allow an EQ on all audio. It still applies to apps that announce their audio " +
                                "(most music players). Videos and games may play without it.",
                        ),
                    ),
                )
            }

            // Profile editor.
            SectionLabel("Profile")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shown.name + if (vm.draft != null) " · edited" else "", style = MaterialTheme.typography.titleMedium)
                    Text(sourceLabel(shown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProfileMenu("Open", lib, includeDefault = false) { id -> id?.let(vm::select) }
            }
            ResponseCurve(shown)
            if (shown.headroomDb > 0.5) {
                Text(
                    "Boosts reach +${"%.1f".format(shown.headroomDb)} dB. A limiter stops clipping, but lowering the preamp sounds cleaner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            GainSlider("Preamp", shown.preampDb, EqMath.MIN_GAIN.toFloat()..EqMath.MAX_PREAMP.toFloat()) { v -> vm.edit(shown) { it.copy(preampDb = v) } }
            if (editable) {
                shown.bands.forEachIndexed { i, b ->
                    GainSlider(frequencyLabel(b.frequencyHz), b.gainDb, EqMath.MIN_GAIN.toFloat()..EqMath.MAX_GAIN.toFloat()) { v ->
                        vm.edit(shown) { it.withBand(i, v) }
                    }
                }
            } else {
                Text(
                    "Imported ${shown.bands.size}-band curve. Adjust the preamp here; re-import to change the curve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.draft != null) {
                    FilledTonalButton(onClick = { vm.save() }) { Text(if (shown.source == EqSource.PRESET) "Save as copy" else "Save") }
                    OutlinedButton(onClick = vm::discard) { Text("Discard") }
                }
                OutlinedButton(onClick = { saveAs = true }) { Text("Save as…") }
                if (lib.defaultProfileId != shown.id && vm.draft == null) OutlinedButton(onClick = { vm.setDefault(shown) }) { Text("Make default") }
                if (shown.source != EqSource.PRESET && vm.draft == null) OutlinedButton(onClick = { vm.delete(shown) }) { Text("Delete") }
                OutlinedButton(onClick = { importer.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }) { Text("Import AutoEQ…") }
            }
            Text(
                "Import a ParametricEQ.txt or GraphicEQ.txt from AutoEQ (github.com/jaakkopasanen/AutoEq) to correct a specific headphone or IEM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Paired devices.
            SectionLabel("Bluetooth devices")
            if (demo) {
                val d = vm.demoSoundcore
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text("Demo device: fake Soundcore earbuds for trying the settings screen", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onSoundcore(d.address, d.name) }) { Text("Settings") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(RedmiBudsRepository.DEMO_NAME, style = MaterialTheme.typography.bodyLarge)
                        Text("Demo device: fake Redmi Buds, including the pairing handshake", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRedmi(RedmiBudsRepository.DEMO_ADDRESS, RedmiBudsRepository.DEMO_NAME) }) { Text("Settings") }
                }
            }
            when {
                !bt.permissionGranted && Build.VERSION.SDK_INT >= 31 -> {
                    Text("Allow Nearby devices so Tugma can list your paired headphones and give each its own EQ profile.", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow") }
                }
                !bt.adapterOn -> Text("Bluetooth is off.", style = MaterialTheme.typography.bodyMedium)
                bt.devices.isEmpty() -> Text("No paired audio devices.", style = MaterialTheme.typography.bodyMedium)
                else -> bt.devices.forEach { d ->
                    DeviceRow(
                        d, lib,
                        soundcore = vm.isSoundcore(d.name), onSoundcore = { onSoundcore(d.address, d.name) },
                        redmi = vm.isRedmi(d.name), onRedmi = { onRedmi(d.address, d.name) },
                    ) { id -> vm.assign(d.eqKey, id, d.name) }
                }
            }

            SectionLabel("Other devices")
            Text(
                "SoundPEATS, Moondrop or something else Tugma doesn't know yet? The protocol lab lets you explore it and import captures of its official app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onLab) { Text("Open protocol lab") }

            // Codec: read-only and honest about it.
            SectionLabel("Codec")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bt.announcedCodec?.let { Text("Active codec: $it", style = MaterialTheme.typography.titleSmall) }
                    if (bt.supportedCodecs.isNotEmpty()) {
                        Text("This phone supports: ${bt.supportedCodecs.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Android doesn't let apps switch the Bluetooth codec (only system apps can), and it rarely tells apps which one is active. " +
                            "Change it under Developer options → Bluetooth audio codec.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            vm.message = "Developer options are off. Tap Build number 7 times in About phone to turn them on."
                            runCatching { context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                        }
                    }) { Text("Open Developer options") }
                }
            }
        }
    }

    if (saveAs) {
        SaveAsDialog(initial = shown.name, onSave = { vm.save(it); saveAs = false }, onDismiss = { saveAs = false })
    }
}

private fun statusText(enabled: Boolean, s: com.timborja.tugma.eq.EqStatus): String = when {
    !enabled -> "Off"
    !s.running -> "Starting…"
    else -> buildString {
        append("On · ${s.profileName ?: "Flat"}")
        append(if (s.globalAttached) " · all audio" else "")
        if (s.sessions.isNotEmpty()) append(" · ${s.sessions.size} app session${if (s.sessions.size == 1) "" else "s"}")
        s.engine?.let { append(" · $it") }
    }
}

private fun sourceLabel(p: EqProfile): String = when (p.source) {
    EqSource.PRESET -> "Built-in preset · ${p.bands.size} bands"
    EqSource.USER -> "Your profile · ${p.bands.size} bands"
    EqSource.AUTOEQ -> "AutoEQ import · ${p.bands.size} bands"
}

private fun outputIcon(kind: OutputDevice.Kind): ImageVector = when (kind) {
    OutputDevice.Kind.BLUETOOTH -> Icons.Outlined.Bluetooth
    OutputDevice.Kind.WIRED -> Icons.Outlined.Headset
    OutputDevice.Kind.USB -> Icons.Outlined.Usb
    OutputDevice.Kind.BUILT_IN -> Icons.Outlined.PhoneAndroid
}

private fun frequencyLabel(hz: Double): String = if (hz >= 1000) "${(hz / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }} kHz" else "${hz.roundToInt()} Hz"

@Composable
private fun GainSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onChange((it * 2).roundToInt() / 2.0) }, // 0.5 dB steps
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text("%+.1f".format(value), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp).padding(start = 6.dp))
    }
}

@Composable
private fun ResponseCurve(p: EqProfile) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val freqs = remember { EqMath.plotFrequencies() }
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val minDb = -15f
        val maxDb = 15f
        fun y(db: Double) = (size.height * (1 - (db.toFloat() - minDb) / (maxDb - minDb))).coerceIn(0f, size.height)
        fun x(f: Double) = (size.width * (ln(f / 20.0) / ln(20_000.0 / 20.0))).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        listOf(-12.0, -6.0, 6.0, 12.0).forEach { db -> drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1f, pathEffect = dash) }
        listOf(100.0, 1000.0, 10_000.0).forEach { f -> drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), 1f, pathEffect = dash) }
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 2f)
        val path = Path()
        freqs.forEachIndexed { i, f ->
            val pt = Offset(x(f), y(EqMath.gainAt(p.bands, f) + p.preampDb))
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("20 Hz", "100", "1k", "10k", "20k").forEachIndexed { i, t ->
            Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (i == 0) Box(Modifier.size(0.dp))
        }
    }
}

@Composable
private fun ProfileMenu(label: String, lib: EqLibrary, includeDefault: Boolean, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (includeDefault) {
                DropdownMenuItem(text = { Text("Default (${lib.profile(lib.defaultProfileId)?.name ?: "Flat"})") }, onClick = { open = false; onPick(null) })
            }
            lib.allProfiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name + when (p.source) { EqSource.AUTOEQ -> " · AutoEQ"; EqSource.USER -> " · yours"; else -> "" }) },
                    onClick = { open = false; onPick(p.id) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    d: BtAudioDevice,
    lib: EqLibrary,
    soundcore: Boolean,
    onSoundcore: () -> Unit,
    redmi: Boolean,
    onRedmi: () -> Unit,
    onAssign: (String?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            when (d.kind) {
                BtAudioDevice.Kind.HEADPHONES -> Icons.Outlined.Headphones
                BtAudioDevice.Kind.EARBUDS -> Icons.Outlined.Headset
                BtAudioDevice.Kind.SPEAKER -> Icons.Outlined.Speaker
                BtAudioDevice.Kind.CAR -> Icons.Outlined.DirectionsCar
                BtAudioDevice.Kind.OTHER -> Icons.Outlined.Bluetooth
            },
            contentDescription = null,
        )
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                if (d.connected) Badge("Connected", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("EQ: ${lib.profileFor(d.eqKey).name}", style = MaterialTheme.typography.bodySmall)
            Text(d.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (soundcore) TextButton(onClick = onSoundcore) { Text("Soundcore settings") }
            if (redmi) TextButton(onClick = onRedmi) { Text("Redmi Buds settings") }
        }
        ProfileMenu("EQ", lib, includeDefault = true, onPick = onAssign)
    }
}

@Composable
private fun SaveAsDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save profile as") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
