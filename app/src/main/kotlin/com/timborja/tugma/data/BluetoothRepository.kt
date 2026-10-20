package com.timborja.tugma.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.timborja.tugma.eq.EqDeviceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class BtAudioDevice(
    val address: String,
    val name: String,
    val kind: Kind,
    val connected: Boolean,
) {
    enum class Kind { HEADPHONES, EARBUDS, SPEAKER, CAR, OTHER }
    val eqKey: String get() = EqDeviceKey.bluetooth(address, name)
}

data class BluetoothUi(
    val permissionGranted: Boolean = false,
    val adapterOn: Boolean = false,
    val devices: List<BtAudioDevice> = emptyList(),
    /** Codecs this phone can use (Android 15+); empty when Android doesn't say. */
    val supportedCodecs: List<String> = emptyList(),
    /** Last codec Android announced through its codec-change broadcast, if any. */
    val announcedCodec: String? = null,
)

/**
 * Paired Bluetooth audio devices. Android gives regular apps no way to read or set the active
 * codec; Tugma shows the phone's supported codecs and listens for codec-change announcements.
 */
class BluetoothRepository(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _state = MutableStateFlow(BluetoothUi())
    val state: StateFlow<BluetoothUi> = _state.asStateFlow()

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    suspend fun refresh() {
        val a = adapter
        if (!hasPermission() || a == null) {
            _state.value = _state.value.copy(permissionGranted = hasPermission(), adapterOn = a?.isEnabled == true, devices = emptyList())
            return
        }
        if (!a.isEnabled) {
            _state.value = _state.value.copy(permissionGranted = true, adapterOn = false, devices = emptyList())
            return
        }
        val connected = buildSet {
            for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET) + if (Build.VERSION.SDK_INT >= 33) listOf(BluetoothProfile.LE_AUDIO) else emptyList()) {
                addAll(connectedOn(profile).map { it.address })
            }
        }
        val devices = a.bondedDevices.orEmpty()
            .filter { d -> d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO || d.address in connected }
            .map { d ->
                BtAudioDevice(
                    address = d.address,
                    name = (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name ?: d.address,
                    kind = kindOf(d.bluetoothClass),
                    connected = d.address in connected,
                )
            }
            .sortedWith(compareBy({ !it.connected }, { it.name.lowercase() }))
        _state.value = _state.value.copy(
            permissionGranted = true,
            adapterOn = true,
            devices = devices,
            supportedCodecs = supportedCodecs(),
        )
    }

    /** Listens for Android's codec-change broadcast; call the returned function to stop. */
    fun listenForCodecChanges(): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (Build.VERSION.SDK_INT < 33) return
                val status = intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)
                val config = status?.codecConfig ?: return
                _state.value = _state.value.copy(announcedCodec = describe(config))
            }
        }
        // Hidden action string; Android may deliver it only to privileged apps. Harmless if it never arrives.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(CODEC_CONFIG_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedOn(profile: Int): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val list = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                        a.closeProfileProxy(p, proxy)
                        if (cont.isActive) cont.resume(list)
                    }
                    override fun onServiceDisconnected(p: Int) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }, profile)
                if (!ok && cont.isActive) cont.resume(emptyList())
            }
        }.orEmpty()
    }

    @SuppressLint("MissingPermission")
    private suspend fun supportedCodecs(): List<String> {
        if (Build.VERSION.SDK_INT < 35) return emptyList()
        val a = adapter ?: return emptyList()
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val names = runCatching { (proxy as BluetoothA2dp).supportedCodecTypes.map { it.codecName } }.getOrDefault(emptyList())
                        a.closeProfileProxy(p, proxy)
                        if (cont.isActive) cont.resume(names)
                    }
                    override fun onServiceDisconnected(p: Int) { if (cont.isActive) cont.resume(emptyList()) }
                }, BluetoothProfile.A2DP)
            }
        }.orEmpty()
    }

    private fun kindOf(c: BluetoothClass?): BtAudioDevice.Kind = when (c?.deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> BtAudioDevice.Kind.HEADPHONES
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> BtAudioDevice.Kind.EARBUDS
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> BtAudioDevice.Kind.SPEAKER
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> BtAudioDevice.Kind.CAR
        else -> BtAudioDevice.Kind.OTHER
    }

    private fun describe(config: BluetoothCodecConfig): String {
        if (Build.VERSION.SDK_INT < 33) return "Unknown"
        val name = if (Build.VERSION.SDK_INT >= 35) {
            config.extendedCodecType?.codecName ?: legacyCodecName(config)
        } else {
            legacyCodecName(config)
        }
        val rate = when (config.sampleRate) {
            BluetoothCodecConfig.SAMPLE_RATE_44100 -> "44.1 kHz"
            BluetoothCodecConfig.SAMPLE_RATE_48000 -> "48 kHz"
            BluetoothCodecConfig.SAMPLE_RATE_88200 -> "88.2 kHz"
            BluetoothCodecConfig.SAMPLE_RATE_96000 -> "96 kHz"
            else -> null
        }
        return listOfNotNull(name, rate).joinToString(" · ")
    }

    /** Android 13–14's integer codec ids, replaced by BluetoothCodecType in Android 15. */
    @RequiresApi(33)
    @Suppress("DEPRECATION")
    private fun legacyCodecName(config: BluetoothCodecConfig): String = when (config.codecType) {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
        else -> "Codec ${config.codecType}"
    }

    private companion object {
        const val CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
    }
}
