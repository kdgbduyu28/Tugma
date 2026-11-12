package com.timborja.tugma.upnp

import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Radio sites often hand out a playlist file (`.m3u`, `.pls`, `.xspf`, `.asx`) instead of the
 * stream itself. Cast receivers and most DLNA renderers can't open those, so the phone reads the
 * playlist and passes on the first stream in it. HLS (`.m3u8`) is a stream format, not a
 * playlist file, and is left alone.
 */
object StreamLinks {
    enum class Format { M3U, PLS, XSPF, ASX }

    /** Decided by the link's extension only: a stream with no extension is never downloaded. */
    fun playlistFormat(url: String): Format? = when (url.trim().substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "m3u" -> Format.M3U
        "pls" -> Format.PLS
        "xspf" -> Format.XSPF
        "asx", "wax", "wvx" -> Format.ASX
        else -> null
    }

    /** The http(s) entries of a playlist, in order. Relative entries are resolved against [base]. */
    fun entries(body: String, format: Format, base: String? = null): List<String> {
        val raw = when (format) {
            Format.M3U -> body.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            Format.PLS -> body.lineSequence().map { it.trim() }
                .mapNotNull { line -> Regex("""^File(\d+)\s*=\s*(.+)$""", RegexOption.IGNORE_CASE).find(line)?.destructured }
                .sortedBy { (n, _) -> n.toIntOrNull() ?: Int.MAX_VALUE }
                .map { (_, url) -> url.trim() }
                .toList()
            Format.XSPF -> Regex("""<location>\s*([^<]+?)\s*</location>""", RegexOption.IGNORE_CASE).findAll(body).map { unescape(it.groupValues[1]) }.toList()
            Format.ASX -> Regex("""<ref\s+href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(body).map { unescape(it.groupValues[1]) }.toList()
        }
        return raw.mapNotNull { absolute(it, base) }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun unescape(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun absolute(entry: String, base: String?): String? = when {
        entry.contains("://") -> entry
        base == null -> null
        else -> runCatching { java.net.URI(base).resolve(entry).toString() }.getOrNull()
    }
}

/**
 * Turns a playlist link into the stream it points at, through an internet-capable [transport]
 * (not the LAN-only one). Anything that isn't a playlist comes back unchanged without a request.
 */
class StreamLinkResolver(
    private val transport: HttpTransport,
    private val timeoutMillis: Long = 8_000,
    /** Playlists inside playlists are rare; one extra level covers them. */
    private val maxDepth: Int = 2,
) {
    suspend fun resolve(url: String): String = resolve(url.trim(), maxDepth)

    private suspend fun resolve(url: String, depth: Int): String {
        val format = StreamLinks.playlistFormat(url) ?: return url
        if (depth == 0) throw IOException("Playlist nests too deeply: $url")
        val response = withTimeout(timeoutMillis) { transport.execute(HttpRequest("GET", url, mapOf("Accept" to "*/*"))) }
        if (response.status !in 200..299) throw IOException("Couldn't read the playlist (HTTP ${response.status})")
        val first = StreamLinks.entries(response.body.take(MAX_CHARS), format, base = url).firstOrNull()
            ?: throw IOException("The playlist has no http(s) stream in it")
        return resolve(first, depth - 1)
    }

    companion object {
        /** Playlist files are tiny; anything longer is cut before parsing. */
        const val MAX_CHARS = 64 * 1024
    }
}
