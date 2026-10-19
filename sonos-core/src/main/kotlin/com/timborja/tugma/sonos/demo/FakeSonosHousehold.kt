package com.timborja.tugma.sonos.demo

import com.timborja.tugma.sonos.ChannelMap
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpResponse
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.SsdpResponse
import com.timborja.tugma.upnp.demo.FakeRenderer
import com.timborja.tugma.upnp.demo.FakeSoap
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.find
import kotlinx.coroutines.delay
import java.net.URI

/**
 * An in-memory household that speaks just enough of the Sonos protocol for tests and the
 * in-app demo mode. Pair/HT commands really change the topology it reports afterwards.
 */
class FakeSonosHousehold(
    players: List<Player> = DEFAULT_PLAYERS,
    initialPairs: Map<String, String> = DEFAULT_PAIRS,
    private val latencyMillis: Long = 0,
) : HttpTransport, Discoverer {

    data class Player(
        val uuid: String,
        val room: String,
        val modelName: String,
        val modelNumber: String,
        val displayName: String,
        val host: String,
        val port: Int = SonosClient.DEFAULT_PORT,
        val softwareVersion: String = "85.0-65020",
        val soundbar: Boolean = false,
    )

    private val players = players.toList()
    private val pairs = initialPairs.toMutableMap()                       // left uuid → right uuid
    private val theaters = mutableMapOf<String, MutableMap<String, String>>() // bar uuid → (satellite uuid → code)

    /** Volume/mute/transport per player UUID. */
    val renderers: Map<String, FakeRenderer> = players.associate { it.uuid to FakeRenderer() }

    /** Every SOAP action received, in order, for test assertions. */
    val receivedActions = mutableListOf<String>()

    override suspend fun search(): List<SsdpResponse> {
        if (latencyMillis > 0) delay(latencyMillis * 3)
        return visiblePlayers().map {
            val location = "http://${it.host}:${it.port}${SonosClient.DEVICE_DESCRIPTION_PATH}"
            SsdpResponse(location, SonosClient.ZONE_PLAYER_ST, "uuid:${it.uuid}::${SonosClient.ZONE_PLAYER_ST}", "Linux UPnP/1.0 Sonos/85.0", emptyMap())
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (latencyMillis > 0) delay(latencyMillis)
        val uri = URI(request.url)
        val player = players.firstOrNull { it.host == uri.host && it.port == (uri.port.takeIf { p -> p > 0 } ?: 80) }
            ?: throw java.net.ConnectException("No route to ${uri.host}")
        return synchronized(this) {
            when {
                request.method == "GET" && uri.path == SonosClient.DEVICE_DESCRIPTION_PATH -> HttpResponse(200, description(player))
                request.method == "GET" && uri.path == SonosClient.DEVICE_PROPERTIES_SCPD -> HttpResponse(200, SCPD)
                request.method == "POST" -> soap(player, uri.path, request)
                else -> HttpResponse(404, "")
            }
        }
    }

    private fun soap(player: Player, path: String, request: HttpRequest): HttpResponse {
        val header = request.headers.entries.firstOrNull { it.key.equals("SOAPACTION", true) }?.value.orEmpty().trim('"')
        val action = header.substringAfter('#')
        receivedActions += action
        val body = Xml.parse(request.body.orEmpty())
        fun arg(name: String) = body.find(name)?.textContent.orEmpty()
        return when {
            path == SonosClient.RC_CONTROL -> renderers.getValue(player.uuid).renderingControl(action, ::arg)
            path == SonosClient.AVT_CONTROL -> {
                // Like real players, only a group coordinator accepts transport commands.
                if (visiblePlayers().none { it.uuid == player.uuid }) fault(800)
                else renderers.getValue(player.uuid).avTransport(action)
            }
            path == SonosClient.ZGT_CONTROL && action == "GetZoneGroupState" ->
                ok(SonosClient.ZGT_SERVICE, action, mapOf("ZoneGroupState" to zoneGroupState()))
            path != SonosClient.DP_CONTROL -> fault(401)
            action == "AddBondedZones" -> addBonded(player, arg("ChannelMapSet"))
            action == "RemoveBondedZones" -> removeBonded(player)
            action == "AddHTSatellite" -> addHt(player, arg("ChannelMapSet"))
            action == "RemoveHTSatellite" -> removeHt(player, arg("SatRoomUUID"))
            else -> fault(401)
        }
    }

    private fun bonded(uuid: String): Boolean =
        uuid in pairs.keys || uuid in pairs.values || uuid in theaters.keys || theaters.values.any { uuid in it }

    private fun addBonded(player: Player, map: String): HttpResponse {
        val entries = ChannelMap.parse(map)
        val left = entries.firstOrNull { it.channelSet == setOf("LF") }?.uuid
        val right = entries.firstOrNull { it.channelSet == setOf("RF") }?.uuid
        if (left == null || right == null || left != player.uuid || left == right) return fault(402)
        if (bonded(left) || bonded(right) || players.none { it.uuid == right }) return fault(800)
        pairs[left] = right
        return ok(SonosClient.DP_SERVICE, "AddBondedZones")
    }

    private fun removeBonded(player: Player): HttpResponse {
        val left = pairs.keys.firstOrNull { it == player.uuid } ?: pairs.entries.firstOrNull { it.value == player.uuid }?.key
            ?: return fault(800)
        pairs.remove(left)
        return ok(SonosClient.DP_SERVICE, "RemoveBondedZones")
    }

    private fun addHt(player: Player, map: String): HttpResponse {
        if (!player.soundbar) return fault(800)
        val entries = ChannelMap.parse(map)
        if (entries.firstOrNull()?.uuid != player.uuid) return fault(402)
        val current = theaters[player.uuid].orEmpty()
        val sats = entries.drop(1)
        for (s in sats) {
            if (s.uuid !in current && bonded(s.uuid)) return fault(800)
            if (players.none { it.uuid == s.uuid }) return fault(402)
        }
        theaters[player.uuid] = sats.associate { it.uuid to it.channels.joinToString(",") }.toMutableMap()
        return ok(SonosClient.DP_SERVICE, "AddHTSatellite")
    }

    private fun removeHt(player: Player, sat: String): HttpResponse {
        val sats = theaters[player.uuid] ?: return fault(800)
        if (sats.remove(sat) == null) return fault(800)
        if (sats.isEmpty()) theaters.remove(player.uuid)
        return ok(SonosClient.DP_SERVICE, "RemoveHTSatellite")
    }

    private fun visiblePlayers(): List<Player> =
        players.filter { p -> p.uuid !in pairs.values && theaters.values.none { p.uuid in it } }

    fun zoneGroupState(): String = buildString {
        append("<ZoneGroupState><ZoneGroups>")
        visiblePlayers().forEachIndexed { i, p ->
            append("<ZoneGroup Coordinator=\"${p.uuid}\" ID=\"${p.uuid}:${100 + i}\">")
            val right = pairs[p.uuid]?.let { r -> players.first { it.uuid == r } }
            val sats = theaters[p.uuid]
            when {
                right != null -> {
                    val map = ChannelMap.stereoPair(p.uuid, right.uuid)
                    append(member("ZoneGroupMember", p, mapOf("ChannelMapSet" to map), close = true))
                    append(member("ZoneGroupMember", right.copy(room = p.room), mapOf("ChannelMapSet" to map, "Invisible" to "1"), close = true))
                }
                sats != null -> {
                    val map = (listOf("${p.uuid}:LF,RF") + sats.map { (u, c) -> "$u:$c" }).joinToString(";")
                    append(member("ZoneGroupMember", p, mapOf("HTSatChanMapSet" to map), close = false))
                    sats.keys.forEach { u ->
                        val sat = players.first { it.uuid == u }.copy(room = p.room)
                        append(member("Satellite", sat, mapOf("HTSatChanMapSet" to map, "Invisible" to "1"), close = true))
                    }
                    append("</ZoneGroupMember>")
                }
                else -> append(member("ZoneGroupMember", p, emptyMap(), close = true))
            }
            append("</ZoneGroup>")
        }
        append("</ZoneGroups><VanishedDevices/></ZoneGroupState>")
    }

    private fun member(tag: String, p: Player, extra: Map<String, String>, close: Boolean): String {
        val attrs = linkedMapOf(
            "UUID" to p.uuid,
            "Location" to "http://${p.host}:${p.port}${SonosClient.DEVICE_DESCRIPTION_PATH}",
            "ZoneName" to p.room,
            "SoftwareVersion" to p.softwareVersion,
            "Configuration" to "1",
        ) + extra
        return "<$tag " + attrs.entries.joinToString(" ") { "${it.key}=\"${Xml.escape(it.value)}\"" } + if (close) "/>" else ">"
    }

    private fun description(p: Player): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
    <friendlyName>${p.host} - ${p.modelName} - ${p.uuid}</friendlyName>
    <manufacturer>Sonos, Inc.</manufacturer>
    <modelNumber>${p.modelNumber}</modelNumber>
    <modelName>${p.modelName}</modelName>
    <displayName>${p.displayName}</displayName>
    <softwareVersion>${p.softwareVersion}</softwareVersion>
    <roomName>${p.room}</roomName>
    <UDN>uuid:${p.uuid}</UDN>
    <serviceList>
      <service>
        <serviceType>${SonosClient.DP_SERVICE}</serviceType>
        <controlURL>${SonosClient.DP_CONTROL}</controlURL>
        <SCPDURL>${SonosClient.DEVICE_PROPERTIES_SCPD}</SCPDURL>
      </service>
      <service>
        <serviceType>${SonosClient.ZGT_SERVICE}</serviceType>
        <controlURL>${SonosClient.ZGT_CONTROL}</controlURL>
        <SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""

    private fun ok(service: String, action: String, out: Map<String, String> = emptyMap()) = FakeSoap.ok(service, action, out)

    private fun fault(code: Int) = FakeSoap.fault(code)

    companion object {
        private fun rincon(n: Int) = "RINCON_DE40A1B2C3%02X01400".format(n)

        val DEFAULT_PLAYERS = listOf(
            Player(rincon(1), "Living Room", "Sonos Arc", "S19", "Arc", "192.168.50.11", soundbar = true),
            Player(rincon(2), "Kitchen", "Sonos One", "S18", "One", "192.168.50.12"),
            Player(rincon(3), "Bedroom", "Sonos Era 100", "S39", "Era 100", "192.168.50.13"),
            Player(rincon(4), "Study", "Sonos Play:1", "S12", "Play:1", "192.168.50.14"),
            Player(rincon(5), "Den", "Sonos Sub Mini", "S36", "Sub Mini", "192.168.50.15"),
            Player(rincon(6), "Office", "Sonos Five", "S33", "Five", "192.168.50.16"),
            Player(rincon(7), "Patio", "Sonos Era 300", "S41", "Era 300", "192.168.50.17"),
            Player(rincon(8), "Loft", "Sonos One SL", "S22", "One SL", "192.168.50.18"),
            Player(rincon(9), "Loft", "Sonos One SL", "S22", "One SL", "192.168.50.19"),
        )

        /** Loft starts as an existing pair so "Separate" can be tried straight away. */
        val DEFAULT_PAIRS = mapOf(rincon(8) to rincon(9))

        private val SCPD = """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>""" +
            listOf("AddBondedZones", "RemoveBondedZones", "CreateStereoPair", "SeparateStereoPair", "AddHTSatellite", "RemoveHTSatellite", "GetZoneInfo")
                .joinToString("") { "<action><name>$it</name></action>" } +
            "</actionList></scpd>"
    }
}

