package com.timborja.tugma.upnp.gena

import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.attr
import com.timborja.tugma.upnp.children
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** One NOTIFY from a device: its subscription id, sequence number and the evented variables. */
data class GenaEvent(val sid: String, val seq: Long, val properties: Map<String, String>)

data class Subscription(val sid: String, val timeoutSeconds: Int, val eventUrl: String)

/**
 * Parses UPnP eventing payloads: the `<e:propertyset>` body and the `LastChange` document that
 * AVTransport and RenderingControl put inside it (UPnP AV spec, also what Sonos sends).
 */
object GenaParser {
    /** Variable name → value from a NOTIFY body. */
    fun propertySet(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val root = runCatching { Xml.parse(body) }.getOrNull() ?: return emptyMap()
        return root.children("property").flatMap { it.children() }.associate { it.localName.orEmpty().ifEmpty { it.tagName } to it.textContent.orEmpty() }
    }

    /**
     * Flattens a `LastChange` document for InstanceID 0. Channelled variables (Volume, Mute)
     * keep only the Master channel.
     */
    fun lastChange(xml: String): Map<String, String> {
        if (xml.isBlank()) return emptyMap()
        val root = runCatching { Xml.parse(xml) }.getOrNull() ?: return emptyMap()
        val instance = root.children("InstanceID").firstOrNull { it.attr("val") == "0" } ?: root.children("InstanceID").firstOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (e in instance.children()) {
            val channel = e.attr("channel")
            if (channel != null && !channel.equals("Master", ignoreCase = true)) continue
            val name = e.localName ?: e.tagName
            out[name] = e.attr("val").orEmpty()
        }
        return out
    }
}

/** SUBSCRIBE / renew / UNSUBSCRIBE, as in UPnP Device Architecture 1.1 §4. */
class GenaClient(private val transport: HttpTransport) {
    suspend fun subscribe(eventUrl: String, callbackUrl: String, timeoutSeconds: Int = DEFAULT_TIMEOUT): Subscription {
        val r = transport.execute(
            HttpRequest("SUBSCRIBE", eventUrl, mapOf("CALLBACK" to "<$callbackUrl>", "NT" to "upnp:event", "TIMEOUT" to "Second-$timeoutSeconds")),
        )
        if (r.status !in 200..299) throw IOException("SUBSCRIBE to $eventUrl returned HTTP ${r.status}")
        val sid = r.header("SID") ?: throw IOException("SUBSCRIBE to $eventUrl returned no SID")
        return Subscription(sid, parseTimeout(r.header("TIMEOUT")) ?: timeoutSeconds, eventUrl)
    }

    suspend fun renew(s: Subscription): Subscription {
        val r = transport.execute(HttpRequest("SUBSCRIBE", s.eventUrl, mapOf("SID" to s.sid, "TIMEOUT" to "Second-${s.timeoutSeconds}")))
        if (r.status !in 200..299) throw IOException("Renewing ${s.sid} returned HTTP ${r.status}")
        return s.copy(timeoutSeconds = parseTimeout(r.header("TIMEOUT")) ?: s.timeoutSeconds)
    }

    suspend fun unsubscribe(s: Subscription) {
        runCatching { transport.execute(HttpRequest("UNSUBSCRIBE", s.eventUrl, mapOf("SID" to s.sid))) }
    }

    companion object {
        const val DEFAULT_TIMEOUT = 300

        fun parseTimeout(v: String?): Int? = v?.trim()?.removePrefix("Second-")?.toIntOrNull()
    }
}

/**
 * A tiny HTTP server that accepts NOTIFY requests on the local network and emits them as
 * [GenaEvent]s. Only NOTIFY with an SID is accepted; everything else gets 400. Bodies are capped.
 */
class GenaServer(private val bind: InetAddress? = null, private val port: Int = 0) {
    private var socket: ServerSocket? = null
    private var job: Job? = null
    private val _events = MutableSharedFlow<GenaEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GenaEvent> = _events.asSharedFlow()

    /** The port actually bound, once started. */
    val localPort: Int get() = socket?.localPort ?: -1

    suspend fun start(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        if (socket != null) return@withContext
        val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port)) }
        socket = s
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) } }
            }
        }
    }

    fun callbackUrl(host: String): String = "http://$host:$localPort/notify"

    fun stop() {
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun handle(c: Socket) = c.use { sock ->
        sock.soTimeout = 5_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = input.readLine() ?: return@use
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
        }
        val length = headers["CONTENT-LENGTH"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length).also { buf ->
            var read = 0
            try {
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
            } catch (_: SocketTimeoutException) {
            }
        }
        val sid = headers["SID"]
        val ok = requestLine.startsWith("NOTIFY ") && sid != null
        sock.getOutputStream().apply {
            write((if (ok) "HTTP/1.1 200 OK" else "HTTP/1.1 400 Bad Request").toByteArray())
            write("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        if (ok) {
            _events.emit(GenaEvent(sid!!, headers["SEQ"]?.toLongOrNull() ?: 0, GenaParser.propertySet(String(body, Charsets.UTF_8))))
        }
    }

    private fun InputStream.readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8_192) sb.append(b.toChar())
        }
    }

    companion object {
        const val MAX_BODY = 256 * 1024
    }
}
