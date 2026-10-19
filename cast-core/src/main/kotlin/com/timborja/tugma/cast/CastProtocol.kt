package com.timborja.tugma.cast

import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.LogEntry
import com.timborja.tugma.upnp.LanAddress
import com.timborja.tugma.upnp.NotLocalNetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** One CASTV2 message (`CastMessage` protobuf from Chromium's cast_channel.proto). */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String?,
) {
    val json: JsonObject? by lazy { payloadUtf8?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } }
    val type: String? get() = json?.get("type")?.jsonPrimitive?.contentOrNull
    val requestId: Int? get() = json?.get("requestId")?.jsonPrimitive?.intOrNull
}

/**
 * Hand-rolled protobuf for CastMessage, so there is no protobuf dependency.
 * Fields: 1 protocol_version (enum, 0 = CASTV2_1_0), 2 source_id, 3 destination_id,
 * 4 namespace, 5 payload_type (0 = STRING), 6 payload_utf8, 7 payload_binary.
 */
object CastFrames {
    fun encode(m: CastMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.varintField(1, 0)
        out.stringField(2, m.sourceId)
        out.stringField(3, m.destinationId)
        out.stringField(4, m.namespace)
        out.varintField(5, 0)
        m.payloadUtf8?.let { out.stringField(6, it) }
        return out.toByteArray()
    }

    /** 4-byte big-endian length prefix + message, as sent on the socket. */
    fun frame(m: CastMessage): ByteArray {
        val body = encode(m)
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    fun decode(bytes: ByteArray): CastMessage {
        var i = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (i >= bytes.size) throw IOException("Truncated varint")
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Varint too long")
            }
        }
        var source = ""
        var dest = ""
        var ns = ""
        var payload: String? = null
        while (i < bytes.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || i + len > bytes.size) throw IOException("Bad length $len")
                    val s = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                    when (field) {
                        2 -> source = s
                        3 -> dest = s
                        4 -> ns = s
                        6 -> payload = s
                    }
                }
                else -> throw IOException("Unsupported wire type in field $field")
            }
        }
        return CastMessage(source, dest, ns, payload)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.varintField(field: Int, value: Long) {
        varint((field shl 3).toLong())
        varint(value)
    }

    private fun ByteArrayOutputStream.stringField(field: Int, value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        varint(((field shl 3) or 2).toLong())
        varint(b.size.toLong())
        write(b)
    }
}

interface CastConnection : Closeable {
    suspend fun send(message: CastMessage)
    /** Suspends until the next message arrives; throws once the connection closes. */
    suspend fun receive(): CastMessage
}

fun interface CastConnector {
    suspend fun connect(host: String, port: Int): CastConnection
}

/**
 * TLS to port 8009. Cast devices present device certificates no public CA signs, so the
 * certificate isn't verified, which is only acceptable because [LanAddress] limits this to the LAN.
 */
class TlsCastConnector(private val connectTimeoutMillis: Int = 4_000) : CastConnector {
    override suspend fun connect(host: String, port: Int): CastConnection = withContext(Dispatchers.IO) {
        if (!LanAddress.isLocal(host)) throw NotLocalNetworkException(host)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustLanDevices), SecureRandom()) }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket.soTimeout = 15_000
        socket.startHandshake()
        SocketCastConnection(socket)
    }

    private object TrustLanDevices : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private class SocketCastConnection(private val socket: SSLSocket) : CastConnection {
    private val input = DataInputStream(socket.inputStream)
    private val output = socket.outputStream

    override suspend fun send(message: CastMessage) = withContext(Dispatchers.IO) {
        synchronized(output) {
            output.write(CastFrames.frame(message))
            output.flush()
        }
    }

    override suspend fun receive(): CastMessage = runInterruptible(Dispatchers.IO) {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME) throw IOException("Bad Cast frame length $length")
        val body = ByteArray(length)
        input.readFully(body)
        CastFrames.decode(body)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_FRAME = 64 * 1024
    }
}

class CastException(message: String) : IOException(message)

object CastNamespaces {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
    const val SENDER = "sender-0"
    const val RECEIVER_ID = "receiver-0"
}

/** One open connection. [request] matches replies by `requestId` and answers heartbeats meanwhile. */
class CastSession internal constructor(
    private val conn: CastConnection,
    private val log: CommandLog?,
    private val label: String,
    private val timeoutMillis: Long,
) {
    private var nextRequestId = 1
    private val connected = mutableSetOf<String>()

    suspend fun connect(destination: String) {
        if (connected.add(destination)) {
            send(CastNamespaces.CONNECTION, destination, buildJsonObject { put("type", JsonPrimitive("CONNECT")) })
        }
    }

    suspend fun send(namespace: String, destination: String, payload: JsonObject) {
        conn.send(CastMessage(CastNamespaces.SENDER, destination, namespace, payload.toString()))
    }

    suspend fun request(namespace: String, destination: String, payload: JsonObject): JsonObject {
        connect(destination)
        val id = nextRequestId++
        val body = JsonObject(payload + ("requestId" to JsonPrimitive(id)))
        val started = System.currentTimeMillis()
        var reply: CastMessage? = null
        var error: String? = null
        try {
            send(namespace, destination, body)
            reply = withTimeout(timeoutMillis) {
                while (true) {
                    val m = conn.receive()
                    if (m.namespace == CastNamespaces.HEARTBEAT && m.type == "PING") {
                        send(CastNamespaces.HEARTBEAT, m.sourceId, buildJsonObject { put("type", JsonPrimitive("PONG")) })
                        continue
                    }
                    if (m.requestId == id) return@withTimeout m
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val json = reply.json ?: throw CastException("Unreadable reply to ${payload["type"]}")
            val type = reply.type
            if (type in ERROR_TYPES) {
                throw CastException("Cast device answered $type" + (json["reason"]?.jsonPrimitive?.contentOrNull?.let { ": $it" } ?: ""))
            }
            return json
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            log?.record(
                LogEntry(
                    id = log.nextId(),
                    timestampMillis = started,
                    method = "CAST",
                    url = "cast://$label/$destination  $namespace",
                    requestHeaders = mapOf("type" to (payload["type"]?.jsonPrimitive?.contentOrNull ?: "?")),
                    requestBody = body.toString(),
                    status = if (error == null) 200 else null,
                    responseBody = reply?.payloadUtf8,
                    durationMillis = System.currentTimeMillis() - started,
                    error = error,
                ),
            )
        }
    }

    internal suspend fun close() {
        connected.forEach { dest ->
            runCatching { send(CastNamespaces.CONNECTION, dest, buildJsonObject { put("type", JsonPrimitive("CLOSE")) }) }
        }
        conn.close()
    }

    companion object {
        private val ERROR_TYPES = setOf("INVALID_REQUEST", "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_PLAYER_STATE", "LAUNCH_ERROR")
    }
}

/** Opens a short-lived session per operation, which keeps state handling simple. */
class CastClient(
    private val connector: CastConnector,
    private val log: CommandLog? = null,
    private val timeoutMillis: Long = 6_000,
) {
    suspend fun <T> session(host: String, port: Int, block: suspend CastSession.() -> T): T {
        val conn = connector.connect(host, port)
        val session = CastSession(conn, log, "$host:$port", timeoutMillis)
        try {
            session.connect(CastNamespaces.RECEIVER_ID)
            return session.block()
        } finally {
            session.close()
        }
    }
}
