package com.timborja.tugma.cast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The Default Media Receiver's LOAD message and what its failures mean. */
object CastMedia {
    /** Content type by extension; the receiver sniffs most streams, but HLS and DASH need the right one. */
    fun contentType(url: String): String = when (url.trim().substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "aac", "adts" -> "audio/aac"
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "webm", "weba" -> "audio/webm"
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> "audio/mpeg"
    }

    fun load(url: String, title: String, live: Boolean): JsonObject = buildJsonObject {
        put("type", "LOAD")
        put("autoplay", true)
        putJsonObject("media") {
            put("contentId", url.trim())
            put("contentUrl", url.trim())
            put("contentType", contentType(url))
            put("streamType", if (live) "LIVE" else "BUFFERED")
            putJsonObject("metadata") {
                put("metadataType", 0)
                put("title", title)
            }
        }
    }

    /** Turns "Cast device answered LOAD_FAILED…" into something a person can act on. */
    fun loadError(raw: String?): String = when {
        raw == null || "LOAD_FAILED" in raw ->
            "The Cast device couldn't play that link. It may be a format Cast doesn't support, or the address may not be reachable from the speaker."
        "LOAD_CANCELLED" in raw -> "Something else started playing before the link loaded."
        "INVALID_REQUEST" in raw -> "The Cast device rejected the request ($raw)."
        else -> raw
    }
}
