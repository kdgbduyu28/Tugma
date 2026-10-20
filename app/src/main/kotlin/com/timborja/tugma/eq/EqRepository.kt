package com.timborja.tugma.eq

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Where audio is going right now, and the key its EQ profile is stored under. */
data class OutputDevice(val key: String, val label: String, val kind: Kind, val address: String? = null) {
    enum class Kind { BUILT_IN, WIRED, USB, BLUETOOTH }
}

data class EqStatus(
    val running: Boolean = false,
    /** True when the EQ is attached to the global output mix (session 0). */
    val globalAttached: Boolean = false,
    val globalError: String? = null,
    /** Per-app audio sessions that apps announced, session id → package. */
    val sessions: Map<Int, String> = emptyMap(),
    val profileName: String? = null,
    val engine: String? = null,
)

/** EQ profiles, per-device assignments, on/off state and the running service's status. */
class EqRepository(private val context: Context) {
    private val file = File(context.filesDir, "eq.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("eq", Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(load())
    val library: StateFlow<EqLibrary> = _library.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** An unsaved profile being edited; the service plays it instead of the assigned one. */
    private val _preview = MutableStateFlow<EqProfile?>(null)
    val preview: StateFlow<EqProfile?> = _preview.asStateFlow()

    private val _status = MutableStateFlow(EqStatus())
    val status: StateFlow<EqStatus> = _status.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean("enabled", on) }
        _enabled.value = on
        val intent = Intent().setComponent(ComponentName(context, EqService::class.java))
        if (on) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
    }

    fun setPreview(profile: EqProfile?) {
        _preview.value = profile
    }

    fun updateStatus(transform: (EqStatus) -> EqStatus) = _status.update(transform)

    fun save(profile: EqProfile) = edit { it.upsert(profile) }
    fun delete(id: String) = edit { it.remove(id) }
    fun assign(deviceKey: String, profileId: String?) = edit { it.assign(deviceKey, profileId) }
    fun setDefault(profileId: String) = edit { it.copy(defaultProfileId = profileId) }

    fun newId(): String = "user:" + UUID.randomUUID().toString().take(8)

    /** Reads an AutoEQ ParametricEQ.txt / GraphicEQ.txt the user picked and saves it as a profile. */
    fun import(uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val text = resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
            require(bytes.size <= MAX_IMPORT_BYTES) { "That file is too big to be an EQ profile" }
            bytes.toString(Charsets.UTF_8)
        } ?: error("Couldn't open the file")
        val name = displayName.orEmpty()
            .removeSuffix(".txt").removeSuffix(" ParametricEQ").removeSuffix(" GraphicEQ").removeSuffix(" FixedBandEQ")
            .ifBlank { "Imported EQ" }
        val result = AutoEqImport.parse(text, "autoeq:" + UUID.randomUUID().toString().take(8), name)
        save(result.profile)
        return result
    }

    private fun edit(change: (EqLibrary) -> EqLibrary) {
        val next = change(_library.value)
        _library.value = next
        file.writeText(json.encodeToString(EqLibrary.serializer(), next))
    }

    private fun load(): EqLibrary =
        runCatching { json.decodeFromString(EqLibrary.serializer(), file.readText()) }.getOrDefault(EqLibrary())

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

    private companion object {
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}

/**
 * Tracks the current media output with AudioDeviceCallback (no Bluetooth permission needed).
 * Android routes media to Bluetooth when connected, then wired/USB, then the speaker.
 */
class OutputMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow(pick())
    val current: StateFlow<OutputDevice> = _current.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { _current.value = pick() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { _current.value = pick() }
        }, Handler(Looper.getMainLooper()))
    }

    private fun pick(): OutputDevice {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val bt = outputs.firstOrNull { it.type in BLUETOOTH_TYPES }
        if (bt != null) {
            val name = bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth audio"
            val address = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { bt.address }.getOrNull() else null
            return OutputDevice(EqDeviceKey.bluetooth(address, name), name, OutputDevice.Kind.BLUETOOTH, address)
        }
        outputs.firstOrNull { it.type in WIRED_TYPES }?.let { return OutputDevice("wired", "Wired headphones", OutputDevice.Kind.WIRED) }
        outputs.firstOrNull { it.type in USB_TYPES }?.let {
            val name = it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "USB audio"
            return OutputDevice("usb:$name", name, OutputDevice.Kind.USB)
        }
        return OutputDevice(EqDeviceKey.BUILT_IN, "Phone speaker", OutputDevice.Kind.BUILT_IN)
    }

    private companion object {
        val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (android.os.Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }
        val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
    }
}
