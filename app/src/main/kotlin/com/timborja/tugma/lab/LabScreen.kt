package com.timborja.tugma.lab

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.bt.lab.BtSnoop
import com.timborja.tugma.bt.lab.CapturedPayload
import com.timborja.tugma.bt.lab.FramingAnalyzer
import com.timborja.tugma.bt.lab.Hex
import com.timborja.tugma.bt.redmibuds.RedmiBudsUuids
import com.timborja.tugma.bt.soundcore.SoundcoreUuids
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import com.timborja.tugma.ui.IssueList
import com.timborja.tugma.ui.MonoBlock
import com.timborja.tugma.ui.ScreenScaffold
import com.timborja.tugma.ui.SectionLabel
import com.timborja.tugma.ui.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class LabSection(val label: String) { RFCOMM("RFCOMM"), BLE("BLE GATT"), USB("USB"), CAPTURE("Capture file"), GUIDE("How to capture") }

class LabViewModel(private val c: AppContainer, private val context: android.app.Application) : ViewModel() {
    val log = LabLog()
    val rfcomm = RfcommLab(context, log)
    val gatt = GattLab(context, log)
    val usb = UsbLab(context, log)

    var section by mutableStateOf(LabSection.RFCOMM)
    var armed by mutableStateOf(false)
    var hex by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    var device by mutableStateOf<String?>(null)
    var uuids by mutableStateOf<List<UUID>>(emptyList())
    var uuid by mutableStateOf<UUID?>(null)
    var insecure by mutableStateOf(false)
    var usbDevices by mutableStateOf<List<UsbInfo>>(emptyList())
    var reportId by mutableStateOf("0")

    var capture by mutableStateOf<List<CapturedPayload>>(emptyList())
    var stream by mutableStateOf<String?>(null)

    fun hasBtPermission() = c.bluetooth.hasPermission()

    private fun bytes(): ByteArray? = runCatching { Hex.parse(hex) }.onFailure { message = it.message }.getOrNull()

    private fun guarded(block: suspend () -> Unit) {
        if (!armed) { message = "Turn on \"I understand\" first"; return }
        viewModelScope.launch { runCatching { block() }.onFailure { message = it.message ?: it.javaClass.simpleName } }
    }

    fun pickDevice(address: String) {
        device = address
        uuids = rfcomm.uuids(address)
        uuid = uuids.firstOrNull()
    }

    fun refreshServices() {
        val d = device ?: return
        viewModelScope.launch { uuids = rfcomm.refreshServices(d); if (uuid == null) uuid = uuids.firstOrNull() }
    }

    fun connectRfcomm() {
        val d = device ?: return
        val u = uuid ?: return
        viewModelScope.launch { runCatching { rfcomm.connect(viewModelScope, d, u, insecure) }.onFailure { message = "Couldn't connect: ${it.message}" } }
    }

    fun sendRfcomm() { val b = bytes() ?: return; guarded { rfcomm.send(b) } }

    fun connectGatt() { val d = device ?: return; runCatching { gatt.connect(d) }.onFailure { message = it.message } }
    fun writeGatt(ch: GattChar) { val b = bytes() ?: return; guarded { gatt.write(ch, b) } }

    fun scanUsb() { usbDevices = usb.devices() }
    fun openUsb(name: String) = usb.open(name) { message = it }
    fun usbOut() { val b = bytes() ?: return; guarded { usb.sendOut(b) } }
    fun usbSetReport() { val b = bytes() ?: return; guarded { usb.setReport(b, reportId.toIntOrNull() ?: 0) } }
    fun usbGetReport() = runCatching { usb.getReport(reportId.toIntOrNull() ?: 0, 64) }.onFailure { message = it.message }

    fun importCapture(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file")
                    require(bytes.size <= 256 * 1024 * 1024) { "Capture is too large" }
                    BtSnoop.parse(bytes)
                }
            }.onSuccess {
                capture = it
                stream = it.groupBy(CapturedPayload::streamKey).maxByOrNull { (_, v) -> v.size }?.key
                message = "Found ${it.size} RFCOMM/GATT payloads"
            }.onFailure { message = it.message }
        }
    }

    /** Framing guess over whatever the device sent in this session or the selected capture stream. */
    fun analyse(packets: List<ByteArray>): String = FramingAnalyzer.analyse(packets).describe()

    override fun onCleared() {
        rfcomm.disconnect()
        gatt.disconnect()
        usb.close()
    }
}

private fun uuidName(u: UUID): String = when {
    u == SoundcoreUuids.SPP -> "Serial Port (SPP)"
    SoundcoreUuids.isVendor(u) -> "Soundcore vendor"
    u == RedmiBudsUuids.CONTROL -> "Xiaomi control (fd2d)"
    u.toString().endsWith("-0000-1000-8000-00805f9b34fb") -> "0x" + u.toString().substring(4, 8)
    else -> u.toString().take(8) + "…"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtocolLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel { LabViewModel(it, context.applicationContext as android.app.Application) }
    val entries by vm.log.entries.collectAsState()
    var hasPermission by rememberSaveable { mutableStateOf(vm.hasBtPermission()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importCapture) }
    fun share(text: String) = context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))

    ScreenScaffold("Protocol lab", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "For working out protocols Tugma doesn't support yet (SoundPEATS, Moondrop, others): talk to a device byte by byte, or import a capture of its official app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabSection.entries.forEach { s -> FilterChip(selected = vm.section == s, onClick = { vm.section = s }, label = { Text(s.label) }) }
            }

            val live = vm.section in listOf(LabSection.RFCOMM, LabSection.BLE, LabSection.USB)
            if (live) {
                IssueList(listOf(Issue(Severity.WARN, "Raw bytes go straight to the device. The wrong ones can change settings, reset it or, rarely, brick it. Only send what you've seen the official app send.")))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I understand", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = vm.armed, onCheckedChange = { vm.armed = it })
                }
            }
            if ((vm.section == LabSection.RFCOMM || vm.section == LabSection.BLE) && !hasPermission && Build.VERSION.SDK_INT >= 31) {
                FilledTonalButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Nearby devices") }
            }

            when (vm.section) {
                LabSection.RFCOMM -> if (hasPermission || Build.VERSION.SDK_INT < 31) RfcommSection(vm)
                LabSection.BLE -> if (hasPermission || Build.VERSION.SDK_INT < 31) BleSection(vm)
                LabSection.USB -> UsbSection(vm)
                LabSection.CAPTURE -> CaptureSection(vm, onImport = { importer.launch(arrayOf("*/*")) }, onShare = ::share)
                LabSection.GUIDE -> GuideSection()
            }

            if (live) {
                OutlinedTextField(
                    value = vm.hex,
                    onValueChange = { vm.hex = it },
                    label = { Text("Bytes to send (hex)") },
                    placeholder = { Text("08 ee 00 00 00 01 01 0a 00 02") },
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Session log")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { share(vm.log.text()) }, enabled = entries.isNotEmpty()) { Text("Share") }
                    OutlinedButton(onClick = vm.log::clear, enabled = entries.isNotEmpty()) { Text("Clear") }
                    OutlinedButton(
                        onClick = { vm.message = null; vm.message = vm.analyse(entries.filter { it.arrow == "←" }.mapNotNull { it.bytes }) },
                        enabled = entries.count { it.arrow == "←" } >= 2,
                    ) { Text("Analyse replies") }
                }
                MonoBlock(entries.takeLast(200).joinToString("\n") { it.toText() }.ifEmpty { "Nothing yet." })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceChips(vm: LabViewModel) {
    val devices = remember(vm) { runCatching { vm.rfcomm.bonded() }.getOrDefault(emptyList()) }
    if (devices.isEmpty()) Text("No paired devices. Pair the device in Android's Bluetooth settings first.", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Suppress("MissingPermission")
        devices.forEach { d -> FilterChip(selected = vm.device == d.address, onClick = { vm.pickDevice(d.address) }, label = { Text(d.name ?: d.address) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RfcommSection(vm: LabViewModel) {
    val connected by vm.rfcomm.connected.collectAsState()
    SectionLabel("Device")
    DeviceChips(vm)
    if (vm.device != null) {
        SectionLabel("Service")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.uuids.forEach { u -> FilterChip(selected = vm.uuid == u, onClick = { vm.uuid = u }, label = { Text(uuidName(u)) }) }
        }
        vm.uuid?.let { Text(it.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::refreshServices) { Text("Re-read services") }
            Checkbox(checked = vm.insecure, onCheckedChange = { vm.insecure = it })
            Text("Insecure socket", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) OutlinedButton(onClick = vm.rfcomm::disconnect) { Text("Disconnect") }
            else Button(onClick = vm::connectRfcomm, enabled = vm.uuid != null) { Text("Connect") }
            Button(onClick = vm::sendRfcomm, enabled = connected && vm.armed) { Text("Send") }
        }
    }
}

@Composable
private fun BleSection(vm: LabViewModel) {
    val state by vm.gatt.state.collectAsState()
    val chars by vm.gatt.characteristics.collectAsState()
    SectionLabel("Device")
    DeviceChips(vm)
    Text("GATT: $state", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::connectGatt, enabled = vm.device != null) { Text("Connect (LE)") }
        OutlinedButton(onClick = vm.gatt::disconnect) { Text("Disconnect") }
    }
    chars.groupBy { it.service }.forEach { (service, list) ->
        Text("Service $service", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        list.forEach { ch ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(10.dp).fillMaxWidth()) {
                    Text(ch.uuid.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(ch.props, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if ("read" in ch.props) TextButton(onClick = { vm.gatt.read(ch) }) { Text("Read") }
                        if ("write" in ch.props) TextButton(onClick = { vm.writeGatt(ch) }, enabled = vm.armed) { Text("Write hex") }
                        if ("notify" in ch.props || "indicate" in ch.props) TextButton(onClick = { vm.gatt.subscribe(ch) }) { Text("Subscribe") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbSection(vm: LabViewModel) {
    val opened by vm.usb.opened.collectAsState()
    Text("Plug the dongle in with a USB-C OTG adapter if needed. Audio-only interfaces can't be opened; DSP controls are usually on a HID interface.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = vm::scanUsb) { Text("List USB devices") }
    vm.usbDevices.forEach { d ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(10.dp).fillMaxWidth()) {
                Text(d.label, style = MaterialTheme.typography.bodyLarge)
                Text("VID 0x%04x · PID 0x%04x".format(d.vendorId, d.productId), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                d.interfaces.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                TextButton(onClick = { vm.openUsb(d.name) }) { Text("Open") }
            }
        }
    }
    opened?.let { label ->
        Text("Open: $label", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = vm.reportId, onValueChange = { vm.reportId = it.filter(Char::isDigit) }, label = { Text("HID report id") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::usbOut, enabled = vm.armed) { Text("Send OUT") }
            Button(onClick = vm::usbSetReport, enabled = vm.armed) { Text("SET_REPORT") }
            OutlinedButton(onClick = { vm.usbGetReport() }) { Text("GET_REPORT") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.usb.startReading(vm.viewModelScope) }) { Text("Read IN") }
            OutlinedButton(onClick = vm.usb::close) { Text("Close") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureSection(vm: LabViewModel, onImport: () -> Unit, onShare: (String) -> Unit) {
    Text("Import an Android btsnoop_hci.log to see what the official app sent and received.", style = MaterialTheme.typography.bodyMedium)
    FilledTonalButton(onClick = onImport) { Text("Import capture…") }
    if (vm.capture.isEmpty()) return
    val streams = vm.capture.groupBy(CapturedPayload::streamKey)
    SectionLabel("Streams")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        streams.forEach { (k, v) -> FilterChip(selected = vm.stream == k, onClick = { vm.stream = k }, label = { Text("$k · ${v.size}") }) }
    }
    val selected = streams[vm.stream].orEmpty()
    if (selected.isNotEmpty()) {
        SectionLabel("Framing guess")
        MonoBlock(vm.analyse(selected.map { it.bytes }))
        val text = selected.joinToString("\n") { "#${it.index} ${if (it.sent) "→" else "←"} ${Hex.format(it.bytes)}" }
        OutlinedButton(onClick = { onShare("${vm.stream}\n$text") }) { Text("Share this stream") }
        SectionLabel("Payloads (first 100)")
        MonoBlock(selected.take(100).joinToString("\n") { "#${it.index} ${if (it.sent) "→" else "←"} ${Hex.format(it.bytes)}" })
    }
}

@Composable
private fun GuideSection() {
    val steps = listOf(
        "Turn on Developer options: Settings → About phone → tap Build number 7 times.",
        "In Developer options, turn on “Enable Bluetooth HCI snoop log” (some phones: set it to “Enabled”, not “Filtered”).",
        "Turn Bluetooth off and on again so logging starts.",
        "Open the official app (SoundPEATS, Moondrop…) and change ONE setting at a time, slowly. Write down what you changed and when, e.g. “14:02:10 EQ → Bass”.",
        "Get the log. On many phones: run `adb bugreport bugreport.zip` on a computer, unzip it, and find FS/data/misc/bluetooth/logs/btsnoop_hci.log. Some phones also save it in internal storage.",
        "Import it under Capture file. Pick the stream with the most payloads (usually RFCOMM from the phone), read the framing guess, and compare payloads before/after each change you noted.",
        "To share findings (or ask for support to be added), use “Share this stream” along with your notes.",
        "USB dongles (e.g. Moondrop DSP): capture on a computer with Wireshark + USBPcap (Windows) while using the vendor's app, then try the same reports here under USB.",
    )
    steps.forEachIndexed { i, s ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${i + 1}.", style = MaterialTheme.typography.titleSmall)
            Text(s, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "The capture contains everything your phone sent over Bluetooth while logging was on (including other devices). Turn logging off afterwards and only share the stream you need.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
