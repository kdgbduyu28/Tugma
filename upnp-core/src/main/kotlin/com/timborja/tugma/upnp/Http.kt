package com.timborja.tugma.upnp

import com.timborja.tugma.core.CommandLog
import com.timborja.tugma.core.LogEntry
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    /** Case-insensitive header lookup. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The seam every protocol client talks through: OkHttp in production, fakes in tests and demo mode. */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val body = request.body?.toRequestBody(contentType?.toMediaTypeOrNull())
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (k, v) ->
            if (!k.equals("Content-Type", ignoreCase = true)) builder.header(k, v)
        }
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        cont.resume(HttpResponse(r.code, r.body?.string().orEmpty(), r.headers.names().associateWith { n -> r.header(n).orEmpty() }))
                    }
                }
            })
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** Records every request and response in [log], including failures. */
class LoggingTransport(
    private val delegate: HttpTransport,
    private val log: CommandLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val started = clock()
        val id = log.nextId()
        try {
            val response = delegate.execute(request)
            log.record(entry(id, started, request, response.status, response.body, null))
            return response
        } catch (e: Exception) {
            log.record(entry(id, started, request, null, null, e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    private fun entry(id: Long, started: Long, request: HttpRequest, status: Int?, body: String?, error: String?) =
        LogEntry(
            id = id,
            timestampMillis = started,
            method = request.method,
            url = request.url,
            requestHeaders = request.headers,
            requestBody = request.body.orEmpty(),
            status = status,
            responseBody = body,
            durationMillis = clock() - started,
            error = error,
        )
}

class NotLocalNetworkException(host: String) :
    IOException("Refusing to contact $host: Tugma only talks to speakers on your local network")

/**
 * Cleartext HTTP is allowed app-wide (Android can't scope it to IP ranges), so this guard makes
 * sure requests only ever reach private/link-local addresses.
 */
class LanOnlyTransport(private val delegate: HttpTransport) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val host = URI(request.url).host ?: throw NotLocalNetworkException(request.url)
        if (!LanAddress.isLocal(host)) throw NotLocalNetworkException(host)
        return delegate.execute(request)
    }
}

object LanAddress {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** True for RFC 1918, loopback, link-local, IPv6 ULA/link-local and `.local` names. Never does DNS. */
    fun isLocal(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        if (ipv4.matches(h)) {
            val o = h.split('.').map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254) ||
                o[0] == 127
        }
        if (h.contains(':')) {
            val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            return addr.isLinkLocalAddress || addr.isLoopbackAddress || addr.isSiteLocalAddress ||
                h.startsWith("fc") || h.startsWith("fd")
        }
        return false
    }
}
