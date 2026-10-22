package com.timborja.tugma.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** A request/response pair as it went over the wire. */
@Serializable
data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int?,
    val responseBody: String?,
    val durationMillis: Long,
    val error: String? = null,
) {
    val isError: Boolean get() = error != null || (status != null && status >= 400)

    /** The same request as a curl command, for replaying it from a computer. Null for non-HTTP entries (Cast). */
    fun toCurl(): String? {
        if (method != "GET" && method != "POST") return null
        fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"
        return buildString {
            append("curl -sS")
            if (method == "POST") append(" -X POST")
            requestHeaders.forEach { (k, v) -> append(" -H ").append(q("$k: $v")) }
            if (method == "POST" && requestBody.isNotEmpty()) append(" --data ").append(q(requestBody))
            append(' ').append(q(url))
        }
    }

    fun toText(): String = buildString {
        appendLine("── $method $url")
        appendLine("time: $timestampMillis  duration: ${durationMillis}ms  status: ${status ?: "-"}")
        requestHeaders.forEach { (k, v) -> appendLine("> $k: $v") }
        if (requestBody.isNotEmpty()) appendLine(requestBody)
        if (error != null) appendLine("! $error")
        if (responseBody != null) {
            appendLine("< response")
            appendLine(responseBody)
        }
    }
}

interface CommandLog {
    val entries: StateFlow<List<LogEntry>>
    fun nextId(): Long
    fun record(entry: LogEntry)
    fun clear()
}

/** Keeps the newest [capacity] entries in memory, newest first. */
class InMemoryCommandLog(private val capacity: Int = 500) : CommandLog {
    private val state = MutableStateFlow<List<LogEntry>>(emptyList())
    private var counter = 0L

    override val entries: StateFlow<List<LogEntry>> = state.asStateFlow()

    @Synchronized
    override fun nextId(): Long = ++counter

    override fun record(entry: LogEntry) {
        state.update { (listOf(entry) + it).take(capacity) }
    }

    override fun clear() {
        state.value = emptyList()
    }
}
