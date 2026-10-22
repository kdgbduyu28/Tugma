package com.timborja.tugma.lab

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.timborja.tugma.bt.lab.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One line of lab traffic. */
data class LabEntry(val timeMillis: Long, val arrow: String, val where: String, val bytes: ByteArray?, val note: String? = null) {
    fun toText(): String = "%tT.%<tL %s %s %s".format(timeMillis, arrow, where, bytes?.let { Hex.format(it) } ?: note.orEmpty()) +
        if (bytes != null && note != null) "  ($note)" else ""
}

/** Shared session log for all three explorers; newest last, capped. */
class LabLog(private val capacity: Int = 2000) {
    private val _entries = MutableStateFlow<List<LabEntry>>(emptyList())
    val entries: StateFlow<List<LabEntry>> = _entries.asStateFlow()

    fun add(arrow: String, where: String, bytes: ByteArray? = null, note: String? = null) {
        _entries.update { (it + LabEntry(System.currentTimeMillis(), arrow, where, bytes, note)).takeLast(capacity) }
    }

    fun clear() { _entries.value = emptyList() }
    fun text(): String = _entries.value.joinToString("\n") { it.toText() }
}

/** Raw RFCOMM socket to a paired device's service UUID. */
@SuppressLint("MissingPermission") // the lab screen asks for BLUETOOTH_CONNECT first
class RfcommLab(private val context: Context, private val log: LabLog) {
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    val connected = MutableStateFlow(false)

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun bonded(): List<BluetoothDevice> = adapter?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }

    fun uuids(address: String): List<UUID> = adapter?.getRemoteDevice(address)?.uuids?.map { it.uuid }.orEmpty()

    /** Asks the device for its current SDP records; Android updates `uuids` a moment later. */
    suspend fun refreshServices(address: String): List<UUID> {
        adapter?.getRemoteDevice(address)?.fetchUuidsWithSdp()
        delay(3_000)
        return uuids(address)
    }

    suspend fun connect(scope: CoroutineScope, address: String, uuid: UUID, insecure: Boolean) {
        disconnect()
        val device = adapter?.getRemoteDevice(address) ?: error("Bluetooth is unavailable")
        val s = withContext(Dispatchers.IO) {
            (if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid) else device.createRfcommSocketToServiceRecord(uuid)).also { it.connect() }
        }
        socket = s
        connected.value = true
        log.add("•", "rfcomm", note = "connected to $address on $uuid")
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    log.add("←", "rfcomm", buf.copyOf(n))
                }
            } catch (e: Exception) {
                log.add("•", "rfcomm", note = "read ended: ${e.message}")
            }
            connected.value = false
        }
    }

    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: error("Not connected")
        s.outputStream.write(bytes)
        s.outputStream.flush()
        log.add("→", "rfcomm", bytes)
    }

    fun disconnect() {
        reader?.cancel()
        runCatching { socket?.close() }
        if (socket != null) log.add("•", "rfcomm", note = "disconnected")
        socket = null
        connected.value = false
    }
}

data class GattChar(val service: UUID, val uuid: UUID, val properties: Int) {
    val props: String get() = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(" ")
}

/** BLE GATT explorer: connect, list characteristics, read, write, subscribe. One operation at a time. */
@SuppressLint("MissingPermission")
class GattLab(private val context: Context, private val log: LabLog) {
    private var gatt: BluetoothGatt? = null
    val state = MutableStateFlow("disconnected")
    val characteristics = MutableStateFlow<List<GattChar>>(emptyList())

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state.value = "connected, discovering…"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state.value = "disconnected (status $status)"
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            characteristics.value = g.services.flatMap { s -> s.characteristics.map { GattChar(s.uuid, it.uuid, it.properties) } }
            state.value = "connected · ${characteristics.value.size} characteristics"
            log.add("•", "gatt", note = "services discovered (status $status)")
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") log.add("←", "read ${c.uuid}", c.value, "status $status")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log.add("←", "read ${c.uuid}", value, "status $status")
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") log.add("←", "notify ${c.uuid}", c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            log.add("←", "notify ${c.uuid}", value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log.add("•", "write ${c.uuid}", note = "status $status")
        }
    }

    fun connect(address: String) {
        disconnect()
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: error("Bluetooth is unavailable")
        state.value = "connecting…"
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun find(c: GattChar) = gatt?.getService(c.service)?.getCharacteristic(c.uuid) ?: error("Characteristic not found")

    fun read(c: GattChar) {
        gatt?.readCharacteristic(find(c))
    }

    @Suppress("DEPRECATION")
    fun write(c: GattChar, bytes: ByteArray) {
        val ch = find(c)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(ch, bytes, type)
        } else {
            ch.writeType = type
            ch.value = bytes
            gatt?.writeCharacteristic(ch)
        }
        log.add("→", "write ${c.uuid}", bytes)
    }

    @Suppress("DEPRECATION")
    fun subscribe(c: GattChar) {
        val g = gatt ?: return
        val ch = find(c)
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: run { log.add("•", "gatt", note = "no CCCD on ${c.uuid}"); return }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value) else { cccd.value = value; g.writeDescriptor(cccd) }
        log.add("•", "gatt", note = "subscribed to ${c.uuid}")
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
        characteristics.value = emptyList()
        state.value = "disconnected"
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

data class UsbInfo(val name: String, val vendorId: Int, val productId: Int, val label: String, val interfaces: List<String>)

/**
 * USB host explorer for DSP dongles (e.g. Moondrop's), which usually expose a HID interface:
 * send on an OUT endpoint or as a HID SET_REPORT, read the IN endpoint or GET_REPORT.
 */
class UsbLab(private val context: Context, private val log: LabLog) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var reader: Job? = null
    val opened = MutableStateFlow<String?>(null)

    fun devices(): List<UsbInfo> = usb.deviceList.values.map { d ->
        val label = runCatching { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") }.getOrNull().orEmpty().ifBlank { d.deviceName }
        UsbInfo(d.deviceName, d.vendorId, d.productId, label, (0 until d.interfaceCount).map { describe(d.getInterface(it)) })
    }

    private fun describe(i: UsbInterface): String {
        val cls = when (i.interfaceClass) {
            UsbConstants.USB_CLASS_HID -> "HID"; UsbConstants.USB_CLASS_AUDIO -> "Audio"; UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor"
            else -> "class ${i.interfaceClass}"
        }
        val eps = (0 until i.endpointCount).joinToString(" ") { e ->
            val ep = i.getEndpoint(e)
            "${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}:0x%02x/%d".format(ep.address, ep.maxPacketSize)
        }
        return "#${i.id} $cls $eps"
    }

    /** Asks for permission (system dialog), then opens and claims the first HID or vendor interface. */
    fun open(name: String, onResult: (String) -> Unit) {
        val device = usb.deviceList[name] ?: return onResult("Device unplugged")
        if (usb.hasPermission(device)) return onResult(claim(device))
        val action = "${context.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                onResult(if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) claim(device) else "Permission denied")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags))
    }

    private fun claim(device: UsbDevice): String {
        close()
        val conn = usb.openDevice(device) ?: return "Couldn't open the device"
        val candidates = (0 until device.interfaceCount).map(device::getInterface)
        val i = candidates.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: candidates.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC }
            ?: return "No HID or vendor interface (audio-only devices can't be controlled here)".also { conn.close() }
        if (!conn.claimInterface(i, true)) return "Couldn't claim interface #${i.id}".also { conn.close() }
        connection = conn
        iface = i
        val eps = (0 until i.endpointCount).map(i::getEndpoint)
        epIn = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        epOut = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val label = "%04x:%04x interface #%d".format(device.vendorId, device.productId, i.id)
        opened.value = label
        log.add("•", "usb", note = "opened $label (IN ${epIn?.address?.let { "0x%02x".format(it) } ?: "none"}, OUT ${epOut?.address?.let { "0x%02x".format(it) } ?: "none"})")
        return "Opened $label"
    }

    /** Polls the IN endpoint and logs whatever arrives. */
    fun startReading(scope: CoroutineScope) {
        val conn = connection ?: return
        val ep = epIn ?: return log.add("•", "usb", note = "no IN endpoint")
        reader?.cancel()
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
            while (isActive) {
                val n = conn.bulkTransfer(ep, buf, buf.size, 250)
                if (n > 0) log.add("←", "usb in", buf.copyOf(n))
            }
        }
    }

    fun sendOut(bytes: ByteArray) {
        val conn = connection ?: error("Not open")
        val ep = epOut ?: error("No OUT endpoint; try SET_REPORT")
        val n = conn.bulkTransfer(ep, bytes, bytes.size, 1_000)
        log.add("→", "usb out", bytes, if (n < 0) "failed" else "$n bytes")
    }

    /** HID class request SET_REPORT (output report). The first byte is the report id when [reportId] > 0. */
    fun setReport(bytes: ByteArray, reportId: Int) {
        val conn = connection ?: error("Not open")
        val i = iface ?: error("Not open")
        val n = conn.controlTransfer(0x21, 0x09, (0x02 shl 8) or reportId, i.id, bytes, bytes.size, 1_000)
        log.add("→", "usb SET_REPORT id=$reportId", bytes, if (n < 0) "failed" else "$n bytes")
    }

    /** HID GET_REPORT (input report). */
    fun getReport(reportId: Int, length: Int) {
        val conn = connection ?: error("Not open")
        val i = iface ?: error("Not open")
        val buf = ByteArray(length)
        val n = conn.controlTransfer(0xA1, 0x01, (0x01 shl 8) or reportId, i.id, buf, length, 1_000)
        if (n < 0) log.add("•", "usb GET_REPORT id=$reportId", note = "failed") else log.add("←", "usb GET_REPORT id=$reportId", buf.copyOf(n))
    }

    fun close() {
        reader?.cancel()
        iface?.let { connection?.releaseInterface(it) }
        connection?.close()
        if (connection != null) log.add("•", "usb", note = "closed")
        connection = null
        iface = null
        opened.value = null
    }
}
