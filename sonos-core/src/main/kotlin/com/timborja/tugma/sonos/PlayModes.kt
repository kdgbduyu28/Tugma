package com.timborja.tugma.sonos

import com.timborja.tugma.core.RepeatMode
import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.childText
import com.timborja.tugma.upnp.children

/** Shuffle and repeat, as Sonos combines them into one `PlayMode` string (SoCo `PLAY_MODES`). */
data class PlayMode(val shuffle: Boolean, val repeat: RepeatMode) {
    val wire: String
        get() = when (repeat) {
            RepeatMode.OFF -> if (shuffle) "SHUFFLE_NOREPEAT" else "NORMAL"
            RepeatMode.ALL -> if (shuffle) "SHUFFLE" else "REPEAT_ALL"
            RepeatMode.ONE -> if (shuffle) "SHUFFLE_REPEAT_ONE" else "REPEAT_ONE"
        }

    companion object {
        fun parse(wire: String): PlayMode? = when (wire.trim().uppercase()) {
            "NORMAL" -> PlayMode(false, RepeatMode.OFF)
            "SHUFFLE_NOREPEAT" -> PlayMode(true, RepeatMode.OFF)
            "SHUFFLE" -> PlayMode(true, RepeatMode.ALL)
            "REPEAT_ALL" -> PlayMode(false, RepeatMode.ALL)
            "SHUFFLE_REPEAT_ONE" -> PlayMode(true, RepeatMode.ONE)
            "REPEAT_ONE" -> PlayMode(false, RepeatMode.ONE)
            else -> null
        }
    }
}

/** Where the group is in the current track. Seconds are null for streams, which have no length. */
data class TrackPosition(val track: Int?, val elapsedSeconds: Int?, val durationSeconds: Int?) {
    val seekable: Boolean get() = durationSeconds != null && durationSeconds > 0
}

/**
 * Shuffle/repeat, crossfade and seeking for a group, sent to its coordinator, as in SoCo
 * `play_mode`, `cross_fade`, `get_current_track_info` and `seek`.
 */
class SonosTransportSettings(private val soap: SoapClient, private val coordinator: Endpoint) {
    private fun avt(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args)

    suspend fun playMode(): PlayMode? = PlayMode.parse(soap.call(avt("GetTransportSettings"))["PlayMode"].orEmpty())

    /** Only works while the group plays from its queue; Sonos answers 712 for radio. */
    suspend fun setPlayMode(mode: PlayMode) {
        soap.call(avt("SetPlayMode", "NewPlayMode" to mode.wire))
    }

    suspend fun crossfade(): Boolean = soap.call(avt("GetCrossfadeMode"))["CrossfadeMode"] == "1"

    suspend fun setCrossfade(on: Boolean) {
        soap.call(avt("SetCrossfadeMode", "CrossfadeMode" to if (on) "1" else "0"))
    }

    suspend fun position(): TrackPosition {
        val out = soap.call(avt("GetPositionInfo"))
        return TrackPosition(
            track = out["Track"]?.toIntOrNull()?.takeIf { it > 0 },
            elapsedSeconds = parseTime(out["RelTime"]),
            durationSeconds = parseTime(out["TrackDuration"])?.takeIf { it > 0 },
        )
    }

    /** Jumps within the current track. */
    suspend fun seek(seconds: Int) {
        soap.call(avt("Seek", "Unit" to "REL_TIME", "Target" to formatTime(seconds)))
    }

    companion object {
        /** "H:MM:SS"; Sonos reports "NOT_IMPLEMENTED" for streams. */
        fun parseTime(value: String?): Int? {
            val parts = value?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
            val (h, m, s) = parts.map { it.substringBefore('.').toIntOrNull() ?: return null }
            return h * 3600 + m * 60 + s
        }

        fun formatTime(seconds: Int): String = seconds.coerceAtLeast(0).let { "%d:%02d:%02d".format(it / 3600, it / 60 % 60, it % 60) }
    }
}

/**
 * Sonos playlists saved in the Sonos app (ContentDirectory `SQ:`), as in SoCo
 * `get_sonos_playlists`. They play through the queue like any container favorite.
 */
class SonosPlaylists(private val soap: SoapClient, private val any: Endpoint) {
    suspend fun list(): List<SonosFavorite> {
        val out = soap.call(
            SoapCall(
                any.baseUrl, SonosFavorites.CD_CONTROL, SonosFavorites.CD_SERVICE, "Browse",
                listOf(
                    "ObjectID" to "SQ:",
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "dc:title,res",
                    "StartingIndex" to "0",
                    "RequestedCount" to "200",
                    "SortCriteria" to "",
                ),
            ),
        )
        return parse(out["Result"].orEmpty())
    }

    companion object {
        fun parse(didl: String): List<SonosFavorite> {
            if (didl.isBlank()) return emptyList()
            return Xml.parse(didl).children("container").mapNotNull { e ->
                val id = e.getAttribute("id")
                val uri = e.childText("res")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = e.childText("title").orEmpty().ifBlank { id }
                SonosFavorite(id = id, title = title, uri = uri, metadata = metadata(id, title), description = "Sonos playlist", albumArtUri = null)
            }
        }

        /** The DIDL AddURIToQueue wants for a saved queue (SoCo's `DidlPlaylistContainer`). */
        fun metadata(id: String, title: String): String =
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                "<container id=\"${Xml.escape(id)}\" parentID=\"SQ:\" restricted=\"true\"><dc:title>${Xml.escape(title)}</dc:title>" +
                "<upnp:class>object.container.playlistContainer</upnp:class>" +
                "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">RINCON_AssociatedZPUDN</desc>" +
                "</container></DIDL-Lite>"
    }
}
