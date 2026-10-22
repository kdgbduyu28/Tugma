package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.childText
import com.timborja.tugma.upnp.children

/** One track in a group's queue. [position] is 1-based, as Sonos numbers them. */
data class QueueItem(val position: Int, val title: String, val artist: String?, val album: String?)

/**
 * The group queue on the coordinator (ContentDirectory `Q:0`), as in SoCo `get_queue`,
 * `play_from_queue`, `remove_from_queue` and `clear_queue`.
 */
class SonosQueue(private val soap: SoapClient, private val coordinator: Endpoint, private val coordinatorUuid: String) {
    private fun avt(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args)

    suspend fun list(max: Int = 500): List<QueueItem> {
        val out = soap.call(
            SoapCall(
                coordinator.baseUrl, SonosFavorites.CD_CONTROL, SonosFavorites.CD_SERVICE, "Browse",
                listOf(
                    "ObjectID" to "Q:0",
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "dc:title,dc:creator,upnp:album",
                    "StartingIndex" to "0",
                    "RequestedCount" to max.toString(),
                    "SortCriteria" to "",
                ),
            ),
        )
        return parse(out["Result"].orEmpty())
    }

    suspend fun playFrom(position: Int) {
        soap.call(avt("SetAVTransportURI", "CurrentURI" to "x-rincon-queue:$coordinatorUuid#0", "CurrentURIMetaData" to ""))
        soap.call(avt("Seek", "Unit" to "TRACK_NR", "Target" to position.toString()))
        soap.call(avt("Play", "Speed" to "1"))
    }

    suspend fun remove(position: Int) {
        soap.call(avt("RemoveTrackFromQueue", "ObjectID" to "Q:0/$position", "UpdateID" to "0"))
    }

    suspend fun clear() {
        soap.call(avt("RemoveAllTracksFromQueue"))
    }

    companion object {
        fun parse(didl: String): List<QueueItem> {
            if (didl.isBlank()) return emptyList()
            return Xml.parse(didl).children("item").mapIndexed { i, e ->
                // Item ids look like "Q:0/3"; fall back to document order.
                val pos = e.getAttribute("id").substringAfterLast('/').toIntOrNull() ?: (i + 1)
                QueueItem(
                    position = pos,
                    title = e.childText("title").orEmpty().ifBlank { "Track $pos" },
                    artist = e.childText("creator")?.takeIf { it.isNotEmpty() },
                    album = e.childText("album")?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}

/**
 * Switching a room to an input: another player's line-in (`x-rincon-stream:`), or a soundbar's
 * TV input (`x-sonos-htastream:<uuid>:spdif`), as in SoCo `switch_to_line_in` / `switch_to_tv`.
 */
class SonosInputs(private val soap: SoapClient, private val coordinator: Endpoint) {
    private fun avt(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args)

    /** Plays [source]'s line-in on this group. The source may be a different room. */
    suspend fun lineIn(source: SonosSpeaker) {
        require(source.hasLineIn) { "${source.roomName} has no line-in" }
        soap.call(avt("SetAVTransportURI", "CurrentURI" to "x-rincon-stream:${source.uuid}", "CurrentURIMetaData" to ""))
        soap.call(avt("Play", "Speed" to "1"))
    }

    /** Switches [bar] to its TV input. Send it to the soundbar itself (it must lead its group). */
    suspend fun tv(bar: SonosSpeaker) {
        require(bar.isSoundbar) { "${bar.roomName} isn't a soundbar" }
        soap.call(avt("SetAVTransportURI", "CurrentURI" to "x-sonos-htastream:${bar.uuid}:spdif", "CurrentURIMetaData" to ""))
    }
}
