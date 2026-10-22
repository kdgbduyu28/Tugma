package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.childText
import com.timborja.tugma.upnp.children

/** One entry of "My Sonos" (ContentDirectory `FV:2`). [metadata] is the DIDL Sonos needs to play it. */
data class SonosFavorite(
    val id: String,
    val title: String,
    val uri: String,
    val metadata: String,
    val description: String?,
    val albumArtUri: String?,
) {
    /**
     * Streams and single tracks play straight from the URI; playlists, albums and other
     * containers have to go through the queue (SoCo `play_uri` / `add_to_queue`).
     */
    val needsQueue: Boolean get() = uri.isBlank() || QUEUE_PREFIXES.any { uri.startsWith(it) }

    private companion object {
        val QUEUE_PREFIXES = listOf("x-rincon-cpcontainer:", "x-rincon-playlist:", "file:///jffs/settings/savedqueues.rsq")
    }
}

class SonosFavorites(private val soap: SoapClient, private val any: Endpoint) {
    suspend fun list(): List<SonosFavorite> {
        val out = soap.call(
            SoapCall(
                any.baseUrl, CD_CONTROL, CD_SERVICE, "Browse",
                listOf(
                    "ObjectID" to "FV:2",
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "dc:title,res,dc:creator,upnp:albumArtURI,r:description",
                    "StartingIndex" to "0",
                    "RequestedCount" to "200",
                    "SortCriteria" to "",
                ),
            ),
        )
        return parse(out["Result"].orEmpty())
    }

    companion object {
        const val CD_SERVICE = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val CD_CONTROL = "/MediaServer/ContentDirectory/Control"

        /** Parses the DIDL-Lite `Result` of a `FV:2` browse. `r:resMD` comes back already unescaped. */
        fun parse(didl: String): List<SonosFavorite> {
            if (didl.isBlank()) return emptyList()
            val root = Xml.parse(didl)
            return (root.children("item") + root.children("container")).map { e ->
                SonosFavorite(
                    id = e.getAttribute("id"),
                    title = e.childText("title").orEmpty(),
                    uri = e.childText("res").orEmpty(),
                    metadata = e.childText("resMD").orEmpty(),
                    description = e.childText("description")?.takeIf { it.isNotEmpty() },
                    albumArtUri = e.childText("albumArtURI")?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }
}

/**
 * Starts things playing on a group coordinator. Playing a container replaces the group's queue,
 * as in the Sonos app.
 */
class SonosPlayer(private val soap: SoapClient, private val coordinator: Endpoint, private val coordinatorUuid: String) {
    private fun avt(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args)

    suspend fun play(favorite: SonosFavorite) {
        if (favorite.needsQueue) {
            soap.call(avt("RemoveAllTracksFromQueue"))
            soap.call(
                avt(
                    "AddURIToQueue",
                    "EnqueuedURI" to favorite.uri,
                    "EnqueuedURIMetaData" to favorite.metadata,
                    "DesiredFirstTrackNumberEnqueued" to "0",
                    "EnqueueAsNext" to "0",
                ),
            )
            soap.call(avt("SetAVTransportURI", "CurrentURI" to "x-rincon-queue:$coordinatorUuid#0", "CurrentURIMetaData" to ""))
        } else {
            soap.call(avt("SetAVTransportURI", "CurrentURI" to favorite.uri, "CurrentURIMetaData" to favorite.metadata))
        }
        soap.call(avt("Play", "Speed" to "1"))
    }
}

/** Stops the group after a while. Sent to the coordinator. */
class SonosSleepTimer(private val soap: SoapClient, private val coordinator: Endpoint) {
    private fun avt(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args)

    /** Seconds left, or null when no timer is set. */
    suspend fun remainingSeconds(): Int? =
        parse(soap.call(avt("GetRemainingSleepTimerDuration"))["RemainingSleepTimerDuration"].orEmpty())

    /** [minutes] null or 0 cancels. */
    suspend fun set(minutes: Int?) {
        val value = if (minutes == null || minutes <= 0) "" else format(minutes.coerceAtMost(MAX_MINUTES) * 60)
        soap.call(avt("ConfigureSleepTimer", "NewSleepTimerDuration" to value))
    }

    companion object {
        const val MAX_MINUTES = 23 * 60 + 59

        fun format(seconds: Int): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

        fun parse(value: String): Int? {
            val parts = value.trim().split(':').map { it.toIntOrNull() ?: return null }
            if (parts.size != 3) return null
            return (parts[0] * 3600 + parts[1] * 60 + parts[2]).takeIf { it > 0 }
        }
    }
}
