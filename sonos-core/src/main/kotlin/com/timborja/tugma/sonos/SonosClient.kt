package com.timborja.tugma.sonos

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.ConfirmedPlan
import com.timborja.tugma.core.PlannedRequest
import com.timborja.tugma.upnp.DeviceDescription
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.Scpd
import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.dlna.ServiceEndpoint
import com.timborja.tugma.upnp.dlna.UpnpPlaybackControl
import java.io.IOException

data class Endpoint(val host: String, val port: Int = SonosClient.DEFAULT_PORT) {
    val baseUrl: String get() = "http://$host:$port"
    override fun toString(): String = if (port == SonosClient.DEFAULT_PORT) host else "$host:$port"
}

class PlanExecutionException(val step: Int, val request: PlannedRequest, cause: Throwable) :
    IOException("Step ${step + 1} (${request.label}) failed: ${cause.message}", cause)

/** Read-only queries against a speaker, plus execution of confirmed plans. */
class SonosClient(private val transport: HttpTransport) {
    private val soap = SoapClient(transport)

    suspend fun describe(endpoint: Endpoint): SonosDeviceInfo =
        SonosDeviceInfo.from(DeviceDescription.parse(get(endpoint, DEVICE_DESCRIPTION_PATH)))

    suspend fun topology(endpoint: Endpoint): Topology {
        val out = soap.call(SoapCall(endpoint.baseUrl, ZGT_CONTROL, ZGT_SERVICE, "GetZoneGroupState"))
        val xml = out["ZoneGroupState"] ?: throw IOException("GetZoneGroupState returned no ZoneGroupState")
        return Topology.parse(xml)
    }

    /** Actions the speaker's DeviceProperties service declares; used to pick modern vs. legacy pairing. */
    suspend fun devicePropertiesActions(endpoint: Endpoint): Set<String> =
        Scpd.actionNames(get(endpoint, DEVICE_PROPERTIES_SCPD))

    /** Sends each request in order, stopping at the first failure. */
    suspend fun execute(confirmed: ConfirmedPlan): List<Map<String, String>> =
        confirmed.plan.requests.mapIndexed { i, req ->
            try {
                soap.send(req)
            } catch (e: Exception) {
                throw PlanExecutionException(i, req, e)
            }
        }

    /**
     * Volume/mute for [speaker] itself; play/pause/next for its group, which Sonos only accepts
     * on the group coordinator.
     */
    fun playback(speaker: Endpoint, coordinator: Endpoint): UpnpPlaybackControl = UpnpPlaybackControl(
        soap,
        renderingControl = ServiceEndpoint(speaker.baseUrl + RC_CONTROL, RC_SERVICE),
        avTransport = ServiceEndpoint(coordinator.baseUrl + AVT_CONTROL, AVT_SERVICE),
        maxVolume = 100,
        urlFor = ::sonosUri,
    )

    /** Bass/treble/loudness and soundbar EQ on [speaker] (the visible player of a bond). */
    fun sound(speaker: Endpoint): SonosSound = SonosSound(soap, speaker)

    /** Whole-group volume; [coordinator] must lead the group. */
    fun groupVolume(coordinator: Endpoint): SonosGroupVolume = SonosGroupVolume(soap, coordinator)

    /** Household alarms; any player answers. */
    fun alarms(any: Endpoint): SonosAlarms = SonosAlarms(soap, any)

    /** "My Sonos" favorites; any player answers. */
    fun favorites(any: Endpoint): SonosFavorites = SonosFavorites(soap, any)

    fun player(coordinator: Endpoint, coordinatorUuid: String): SonosPlayer = SonosPlayer(soap, coordinator, coordinatorUuid)

    fun queue(coordinator: Endpoint, coordinatorUuid: String): SonosQueue = SonosQueue(soap, coordinator, coordinatorUuid)

    fun inputs(coordinator: Endpoint): SonosInputs = SonosInputs(soap, coordinator)

    /** Status light, touch lock, Trueplay and room name on [speaker] itself. */
    fun settings(speaker: Endpoint): SonosSettings = SonosSettings(soap, speaker)

    fun sleepTimer(coordinator: Endpoint): SonosSleepTimer = SonosSleepTimer(soap, coordinator)

    private suspend fun get(endpoint: Endpoint, path: String): String {
        val r = transport.execute(HttpRequest("GET", endpoint.baseUrl + path))
        if (r.status !in 200..299) throw IOException("GET $path on $endpoint returned HTTP ${r.status}")
        return r.body
    }

    companion object {
        /** Sonos plays plain HTTP radio only as `x-rincon-mp3radio:` (SoCo `play_uri(force_radio=True)`). */
        fun sonosUri(url: String, live: Boolean): String =
            if (live && url.startsWith("http://", ignoreCase = true)) "x-rincon-mp3radio:" + url.substringAfter(':') else url

        const val DEFAULT_PORT = 1400
        const val DEVICE_DESCRIPTION_PATH = "/xml/device_description.xml"
        const val DEVICE_PROPERTIES_SCPD = "/xml/DeviceProperties1.xml"
        const val DP_SERVICE = "urn:schemas-upnp-org:service:DeviceProperties:1"
        const val DP_CONTROL = "/DeviceProperties/Control"
        const val ZGT_SERVICE = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        const val ZGT_CONTROL = "/ZoneGroupTopology/Control"
        const val ZONE_PLAYER_ST = "urn:schemas-upnp-org:device:ZonePlayer:1"
        const val RC_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val RC_CONTROL = "/MediaRenderer/RenderingControl/Control"
        const val AVT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val AVT_CONTROL = "/MediaRenderer/AVTransport/Control"
    }
}

/**
 * Builds the exact SOAP requests for each write. Pure: nothing here touches the network.
 * Formats follow SoCo `core.py` and erikmansson/pair-sonos.
 */
object SonosPlanner {
    const val ADD_BONDED = "AddBondedZones"
    const val REMOVE_BONDED = "RemoveBondedZones"
    const val LEGACY_CREATE = "CreateStereoPair"
    const val LEGACY_SEPARATE = "SeparateStereoPair"
    const val ADD_HT = "AddHTSatellite"
    const val REMOVE_HT = "RemoveHTSatellite"

    /** Modern firmware lists AddBondedZones; only fall back to CreateStereoPair when it doesn't. */
    fun useLegacyPairing(actions: Set<String>?): Boolean =
        actions != null && ADD_BONDED !in actions && LEGACY_CREATE in actions

    fun stereoPair(left: SonosSpeaker, right: SonosSpeaker, legacy: Boolean, warnings: List<String> = emptyList()): CommandPlan {
        val map = ChannelMap.stereoPair(left.uuid, right.uuid)
        val action = if (legacy) LEGACY_CREATE else ADD_BONDED
        return CommandPlan(
            title = "Stereo pair ${left.displayName} + ${right.displayName}",
            summary = "${left.displayName} becomes the left speaker and group coordinator; " +
                "${right.displayName} becomes the right speaker.",
            requests = listOf(dp(left.endpoint, action, listOf("ChannelMapSet" to map), "$action → ${left.displayName}")),
            warnings = warnings,
        )
    }

    fun separateStereoPair(target: SonosSpeaker, currentChannelMap: String?, legacy: Boolean): CommandPlan {
        val (action, args) = if (legacy) {
            LEGACY_SEPARATE to listOf("ChannelMapSet" to currentChannelMap.orEmpty())
        } else {
            REMOVE_BONDED to listOf("ChannelMapSet" to "", "KeepGrouped" to "0")
        }
        return CommandPlan(
            title = "Separate stereo pair ${target.displayName}",
            summary = "Splits the pair back into two independent speakers.",
            requests = listOf(dp(target.endpoint, action, args, "$action → ${target.displayName}")),
        )
    }

    /** [fullMap] must include the soundbar's own `LF,RF` entry and every satellite, old and new. */
    fun addHtSatellites(bar: SonosSpeaker, fullMap: String, summary: String, warnings: List<String>): CommandPlan =
        CommandPlan(
            title = "Home theater for ${bar.displayName}",
            summary = summary,
            requests = listOf(dp(bar.endpoint, ADD_HT, listOf("ChannelMapSet" to fullMap), "$ADD_HT → ${bar.displayName}")),
            warnings = warnings,
        )

    fun removeHtSatellite(bar: SonosSpeaker, satelliteUuid: String, satelliteName: String): CommandPlan =
        CommandPlan(
            title = "Remove $satelliteName from ${bar.displayName}",
            summary = "$satelliteName goes back to being a standalone speaker. " +
                "Sonos may reset Trueplay tuning on the soundbar.",
            requests = listOf(dp(bar.endpoint, REMOVE_HT, listOf("SatRoomUUID" to satelliteUuid), "$REMOVE_HT → ${bar.displayName}")),
        )

    fun dp(endpoint: Endpoint, action: String, args: List<Pair<String, String>>, label: String): PlannedRequest =
        SoapCall(endpoint.baseUrl, SonosClient.DP_CONTROL, SonosClient.DP_SERVICE, action, args).toPlannedRequest(label)
}
