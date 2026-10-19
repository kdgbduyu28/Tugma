package com.timborja.tugma.upnp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI

data class SsdpResponse(
    val location: String,
    val st: String?,
    val usn: String?,
    val server: String?,
    val headers: Map<String, String>,
) {
    val host: String get() = URI(location).host
    val port: Int get() = URI(location).port.takeIf { it > 0 } ?: 80
}

/** Finds UPnP devices with an M-SEARCH. Platform code (e.g. Android's MulticastLock) wraps [search]. */
interface Discoverer {
    suspend fun search(): List<SsdpResponse>
}

class SsdpDiscoverer(
    private val searchTarget: String,
    private val windowMillis: Int = 3000,
    private val repeats: Int = 3,
    private val mx: Int = 1,
) : Discoverer {

    override suspend fun search(): List<SsdpResponse> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, SsdpResponse>()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 250
            val payload = mSearch(searchTarget, mx).toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            val packet = DatagramPacket(payload, payload.size, group, MULTICAST_PORT)
            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + windowMillis
            var sent = 0
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (sent < repeats && now >= nextSend) {
                    socket.send(packet)
                    sent++
                    nextSend = now + 400
                }
                val reply = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(reply)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(reply.data, 0, reply.length, Charsets.UTF_8)
                parseResponse(text)?.let { r ->
                    if (r.st == null || r.st.equals(searchTarget, ignoreCase = true)) found[r.location] = r
                }
            }
        }
        found.values.toList()
    }

    companion object {
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val MULTICAST_PORT = 1900

        fun mSearch(st: String, mx: Int): String =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "\r\n"

        /** Parses an `HTTP/1.1 200 OK` search reply. Returns null for NOTIFYs or replies without LOCATION. */
        fun parseResponse(text: String): SsdpResponse? {
            val lines = text.split("\r\n", "\n")
            if (lines.isEmpty() || !lines[0].startsWith("HTTP/", ignoreCase = true)) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
            }
            val location = headers["LOCATION"] ?: return null
            return SsdpResponse(location, headers["ST"], headers["USN"], headers["SERVER"], headers)
        }
    }
}
