package com.timborja.tugma.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.timborja.tugma.bt.soundcore.BtTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.UUID

/** RFCOMM socket as a [BtTransport]. */
internal class RfcommTransport(private val socket: BluetoothSocket) : BtTransport {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val buffer = ByteArray(1024)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun read(): ByteArray = runInterruptible(Dispatchers.IO) {
        val n = input.read(buffer)
        if (n < 0) throw java.io.IOException("Connection closed")
        buffer.copyOf(n)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}


/** Opens an RFCOMM channel to [address] on [uuid]. Needs BLUETOOTH_CONNECT. */
@SuppressLint("MissingPermission")
internal suspend fun openRfcomm(context: Context, address: String, uuid: UUID): RfcommTransport {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is unavailable")
    val device = adapter.getRemoteDevice(address)
    val socket = withContext(Dispatchers.IO) { device.createRfcommSocketToServiceRecord(uuid).also { it.connect() } }
    return RfcommTransport(socket)
}
