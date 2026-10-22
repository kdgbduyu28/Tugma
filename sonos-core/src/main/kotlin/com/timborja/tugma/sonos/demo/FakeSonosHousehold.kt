package com.timborja.tugma.sonos.demo

import com.timborja.tugma.sonos.ChannelMap
import com.timborja.tugma.sonos.SonosAlarm
import com.timborja.tugma.sonos.SonosAlarms
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.sonos.SonosFavorite
import com.timborja.tugma.sonos.SonosFavorites
import com.timborja.tugma.sonos.SonosSleepTimer
import com.timborja.tugma.upnp.dlna.Didl
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
        /** Has up-firing drivers (Arc, Era 300…), so it reports a height level. */
        val height: Boolean = false,
        val lineIn: Boolean = false,
    )

    private val players = players.toList()
    private val pairs = initialPairs.toMutableMap()                       // left uuid → right uuid
    private val theaters = mutableMapOf<String, MutableMap<String, String>>() // bar uuid → (satellite uuid → code)
    private val joins = mutableMapOf<String, String>()                    // member uuid → coordinator uuid
    private val soundValues = mutableMapOf<String, MutableMap<String, Int>>()   // player uuid → setting → value
    private val names = mutableMapOf<String, String>()                    // renamed rooms
    private val led = mutableMapOf<String, Boolean>()
    private val locked = mutableMapOf<String, Boolean>()
    private val queues = mutableMapOf<String, MutableList<Pair<String, String>>>() // coordinator → (uri, metadata)
    private val sleepSeconds = mutableMapOf<String, Int>()
    private val alarms = mutableListOf<SonosAlarm>()
    private var nextAlarmId = 1

    /** DesiredIcon of the last SetZoneAttributes, for test assertions. */
    var lastZoneIcon: String? = null

    /** What each player was last told to play (SetAVTransportURI), for test assertions. */
    val currentUri = mutableMapOf<String, String>()

    /** The "My Sonos" favorites the fake serves. Names are made up on purpose. */
    val favorites: List<SonosFavorite> = DEFAULT_FAVORITES

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
            path == SonosClient.RC_CONTROL && action in SOUND_ACTIONS -> sound(player, action, ::arg)
            path == SonosClient.RC_CONTROL -> renderers.getValue(player.uuid).renderingControl(action, ::arg)
            path == SonosClient.AVT_CONTROL && action == "SetAVTransportURI" -> setUri(player, arg("CurrentURI"), arg("CurrentURIMetaData"))
            path == SonosClient.AVT_CONTROL && action in QUEUE_ACTIONS -> queueOrTimer(player, action, ::arg)
            path == SonosFavorites.CD_CONTROL -> browse(player, arg("ObjectID"))
            path == SonosAlarms.CONTROL -> alarmClock(action, ::arg)
            path == SonosClient.AVT_CONTROL && action == "BecomeCoordinatorOfStandaloneGroup" -> leave(player)
            path == SonosClient.AVT_CONTROL -> {
                // Like real players, only a group coordinator accepts transport commands.
                if (visiblePlayers().none { it.uuid == player.uuid } || coordinatorOf(player.uuid) != player.uuid) fault(800)
                else renderers.getValue(player.uuid).avTransport(action)
            }
            path == GRC_CONTROL -> groupRendering(player, action, ::arg)
            path == SonosClient.ZGT_CONTROL && action == "GetZoneGroupState" ->
                ok(SonosClient.ZGT_SERVICE, action, mapOf("ZoneGroupState" to zoneGroupState()))
            path != SonosClient.DP_CONTROL -> fault(401)
            action == "GetLEDState" -> ok(SonosClient.DP_SERVICE, action, mapOf("CurrentLEDState" to if (led[player.uuid] != false) "On" else "Off"))
            action == "SetLEDState" -> arg("DesiredLEDState").takeIf { it == "On" || it == "Off" }
                ?.let { led[player.uuid] = it == "On"; ok(SonosClient.DP_SERVICE, action) } ?: fault(402)
            action == "GetButtonLockState" -> ok(SonosClient.DP_SERVICE, action, mapOf("CurrentButtonLockState" to if (locked[player.uuid] == true) "On" else "Off"))
            action == "SetButtonLockState" -> arg("DesiredButtonLockState").takeIf { it == "On" || it == "Off" }
                ?.let { locked[player.uuid] = it == "On"; ok(SonosClient.DP_SERVICE, action) } ?: fault(402)
            action == "GetZoneAttributes" -> ok(
                SonosClient.DP_SERVICE, action,
                mapOf("CurrentZoneName" to (names[player.uuid] ?: player.room), "CurrentIcon" to "x-rincon-roomicon:living", "CurrentConfiguration" to "1"),
            )
            action == "SetZoneAttributes" -> arg("DesiredZoneName").trim().takeIf { it.isNotEmpty() }
                ?.let { n -> names[player.uuid] = n; lastZoneIcon = arg("DesiredIcon"); ok(SonosClient.DP_SERVICE, action) } ?: fault(402)
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

    /** The coordinator [uuid] plays along with; itself when standalone or its coordinator got bonded away. */
    fun coordinatorOf(uuid: String): String {
        val c = joins[uuid] ?: return uuid
        return if (visiblePlayers().any { it.uuid == c }) c else uuid
    }

    /** When a coordinator leaves, the rest of its group stays together under the first remaining member. */
    private fun handOver(uuid: String) {
        val followers = joins.filterValues { it == uuid }.keys.toList()
        if (followers.isEmpty()) return
        val next = followers.first()
        joins.remove(next)
        followers.drop(1).forEach { joins[it] = next }
    }

    private fun setUri(player: Player, uri: String, metadata: String): HttpResponse {
        if (visiblePlayers().none { it.uuid == player.uuid }) return fault(800)
        if (!uri.startsWith("x-rincon:")) {
            // Playing something of its own takes the player out of its group.
            joins.remove(player.uuid)
            currentUri[player.uuid] = uri
            val r = renderers.getValue(player.uuid)
            when {
                uri.startsWith("x-rincon-stream:") -> {
                    val src = players.firstOrNull { it.uuid == uri.removePrefix("x-rincon-stream:") }
                    if (src == null || !src.lineIn) return fault(714)
                    r.title = "Line-in"; r.artist = src.room
                }
                uri.startsWith("x-sonos-htastream:") -> {
                    if (!player.soundbar || uri != "x-sonos-htastream:${player.uuid}:spdif") return fault(714)
                    r.title = "TV"; r.artist = player.room; r.transport = "PLAYING"
                }
                else -> {
                    val meta = if (uri.startsWith("x-rincon-queue:")) queues[player.uuid]?.firstOrNull()?.second.orEmpty() else metadata
                    Didl.parse(meta)?.let { t -> r.title = t.title; r.artist = t.artist }
                }
            }
            currentUri[player.uuid] = uri
            return ok(SonosClient.AVT_SERVICE, "SetAVTransportURI")
        }
        val target = uri.removePrefix("x-rincon:")
        if (target == player.uuid || visiblePlayers().none { it.uuid == target } || coordinatorOf(target) != target) return fault(800)
        if (coordinatorOf(player.uuid) == target) return ok(SonosClient.AVT_SERVICE, "SetAVTransportURI")
        handOver(player.uuid)
        joins[player.uuid] = target
        return ok(SonosClient.AVT_SERVICE, "SetAVTransportURI")
    }

    private fun leave(player: Player): HttpResponse {
        if (visiblePlayers().none { it.uuid == player.uuid }) return fault(800)
        if (joins.remove(player.uuid) == null) handOver(player.uuid)
        return ok(SonosClient.AVT_SERVICE, "BecomeCoordinatorOfStandaloneGroup")
    }

    private fun queueOrTimer(player: Player, action: String, arg: (String) -> String): HttpResponse {
        if (visiblePlayers().none { it.uuid == player.uuid } || coordinatorOf(player.uuid) != player.uuid) return fault(800)
        val svc = SonosClient.AVT_SERVICE
        return when (action) {
            "RemoveAllTracksFromQueue" -> { queues.remove(player.uuid); ok(svc, action) }
            "RemoveTrackFromQueue" -> {
                val n = arg("ObjectID").substringAfterLast('/').toIntOrNull()
                val q = queues[player.uuid]
                if (n == null || q == null || n !in 1..q.size) fault(701) else { q.removeAt(n - 1); ok(svc, action) }
            }
            "Seek" -> {
                val q = queues[player.uuid].orEmpty()
                val n = arg("Target").toIntOrNull()
                if (arg("Unit") != "TRACK_NR" || n == null || n !in 1..q.size) fault(711)
                else { Didl.parse(q[n - 1].second)?.let { t -> renderers.getValue(player.uuid).apply { title = t.title; artist = t.artist } }; ok(svc, action) }
            }
            "AddURIToQueue" -> {
                val q = queues.getOrPut(player.uuid) { mutableListOf() }
                val uri = arg("EnqueuedURI")
                val name = Didl.parse(arg("EnqueuedURIMetaData"))?.title ?: "Demo"
                // Containers expand to a few made-up tracks, like a real album or playlist would.
                if (uri.startsWith("x-rincon-cpcontainer:")) (1..3).forEach { n -> q += "$uri#$n" to didlItem("$name, track $n", "object.item.audioItem.musicTrack") }
                else q += uri to arg("EnqueuedURIMetaData")
                ok(svc, action, mapOf("FirstTrackNumberEnqueued" to q.size.toString(), "NumTracksAdded" to "1", "NewQueueLength" to q.size.toString()))
            }
            "ConfigureSleepTimer" -> {
                val v = arg("NewSleepTimerDuration")
                if (v.isEmpty()) { sleepSeconds.remove(player.uuid); ok(svc, action) }
                else SonosSleepTimer.parse(v)?.let { sleepSeconds[player.uuid] = it; ok(svc, action) } ?: fault(402)
            }
            "GetRemainingSleepTimerDuration" -> ok(
                svc, action,
                mapOf("RemainingSleepTimerDuration" to (sleepSeconds[player.uuid]?.let(SonosSleepTimer::format) ?: ""), "CurrentSleepTimerGeneration" to "1"),
            )
            else -> fault(401)
        }
    }

    /** Tracks queued on a coordinator, for test assertions. */
    fun queue(uuid: String): List<String> = queues[uuid].orEmpty().map { it.first }

    private fun browse(player: Player, objectId: String): HttpResponse {
        if (objectId == "Q:0") {
            val q = queues[coordinatorOf(player.uuid)].orEmpty()
            val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                q.mapIndexed { i, (_, meta) ->
                    "<item id=\"Q:0/${i + 1}\" parentID=\"Q:0\" restricted=\"true\"><dc:title>${Xml.escape(Didl.parse(meta)?.title ?: "Track ${i + 1}")}</dc:title>" +
                        "<dc:creator>Demo Artist</dc:creator><upnp:class>object.item.audioItem.musicTrack</upnp:class></item>"
                }.joinToString("") + "</DIDL-Lite>"
            return ok(SonosFavorites.CD_SERVICE, "Browse", mapOf("Result" to didl, "NumberReturned" to q.size.toString(), "TotalMatches" to q.size.toString(), "UpdateID" to "1"))
        }
        if (objectId != "FV:2") return fault(701)
        val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            favorites.joinToString("") { f ->
                "<item id=\"${f.id}\" parentID=\"FV:2\" restricted=\"false\"><dc:title>${Xml.escape(f.title)}</dc:title>" +
                    "<upnp:class>object.itemobject.item.sonos-favorite</upnp:class>" +
                    (f.albumArtUri?.let { "<upnp:albumArtURI>${Xml.escape(it)}</upnp:albumArtURI>" } ?: "") +
                    (f.description?.let { "<r:description>${Xml.escape(it)}</r:description>" } ?: "") +
                    "<res protocolInfo=\"sonos.com-http:*:audio/mpeg:*\">${Xml.escape(f.uri)}</res>" +
                    "<r:resMD>${Xml.escape(f.metadata)}</r:resMD></item>"
            } + "</DIDL-Lite>"
        return ok(SonosFavorites.CD_SERVICE, "Browse", mapOf("Result" to didl, "NumberReturned" to favorites.size.toString(), "TotalMatches" to favorites.size.toString(), "UpdateID" to "1"))
    }

    private fun alarmClock(action: String, arg: (String) -> String): HttpResponse {
        val svc = SonosAlarms.SERVICE
        fun fromArgs(id: String?) = SonosAlarm(
            id = id,
            startTime = arg("StartLocalTime"),
            durationOrBlank = arg("Duration"),
            recurrence = arg("Recurrence"),
            enabled = arg("Enabled") == "1",
            roomUuid = arg("RoomUUID"),
            programUri = arg("ProgramURI"),
            programMetaData = arg("ProgramMetaData"),
            playMode = arg("PlayMode"),
            volume = arg("Volume").toIntOrNull() ?: -1,
            includeLinkedZones = arg("IncludeLinkedZones") == "1",
        )
        fun valid(a: SonosAlarm) = a.problems().isEmpty() && a.volume in 0..100 && players.any { it.uuid == a.roomUuid }
        return when (action) {
            "ListAlarms" -> ok(
                svc, action,
                mapOf(
                    "CurrentAlarmList" to "<Alarms>" + alarms.joinToString("") { a ->
                        "<Alarm ID=\"${a.id}\" StartTime=\"${a.startTime}\" Duration=\"${a.durationOrBlank}\" Recurrence=\"${a.recurrence}\" " +
                            "Enabled=\"${if (a.enabled) 1 else 0}\" RoomUUID=\"${a.roomUuid}\" ProgramURI=\"${Xml.escape(a.programUri)}\" " +
                            "ProgramMetaData=\"${Xml.escape(a.programMetaData)}\" PlayMode=\"${a.playMode}\" Volume=\"${a.volume}\" " +
                            "IncludeLinkedZones=\"${if (a.includeLinkedZones) 1 else 0}\"/>"
                    } + "</Alarms>",
                    "CurrentAlarmListVersion" to "RINCON_FAKE:${alarms.size}",
                ),
            )
            "CreateAlarm" -> fromArgs((nextAlarmId++).toString()).takeIf(::valid)?.let { alarms += it; ok(svc, action, mapOf("AssignedID" to it.id!!)) } ?: fault(402)
            "UpdateAlarm" -> {
                val i = alarms.indexOfFirst { it.id == arg("ID") }
                if (i < 0) fault(801) else fromArgs(arg("ID")).takeIf(::valid)?.let { alarms[i] = it; ok(svc, action) } ?: fault(402)
            }
            "DestroyAlarm" -> if (alarms.removeIf { it.id == arg("ID") }) ok(svc, action) else fault(801)
            else -> fault(401)
        }
    }

    private fun groupOf(coordinator: String): List<String> =
        visiblePlayers().map { it.uuid }.filter { coordinatorOf(it) == coordinator }

    private fun groupRendering(player: Player, action: String, arg: (String) -> String): HttpResponse {
        if (coordinatorOf(player.uuid) != player.uuid || visiblePlayers().none { it.uuid == player.uuid }) return fault(701)
        val members = groupOf(player.uuid).map(renderers::getValue)
        return when (action) {
            "GetGroupVolume" -> ok(GRC_SERVICE, action, mapOf("CurrentVolume" to (members.sumOf { it.volume } / members.size).toString()))
            "SnapshotGroupVolume" -> ok(GRC_SERVICE, action)
            "SetGroupVolume" -> arg("DesiredVolume").toIntOrNull()?.takeIf { it in 0..100 }
                ?.let { v -> members.forEach { it.volume = v }; ok(GRC_SERVICE, action) } ?: fault(402)
            "GetGroupMute" -> ok(GRC_SERVICE, action, mapOf("CurrentMute" to if (members.all { it.muted }) "1" else "0"))
            "SetGroupMute" -> { val m = arg("DesiredMute") == "1"; members.forEach { it.muted = m }; ok(GRC_SERVICE, action) }
            else -> fault(401)
        }
    }

    /** Which EQ types [p] reports right now: soundbar ones, plus sub/surround ones once those satellites exist. */
    private fun eqTypes(p: Player): Set<String> = buildSet {
        if (!p.soundbar) return@buildSet
        addAll(listOf("NightMode", "DialogLevel", "AudioDelay"))
        val sats = theaters[p.uuid].orEmpty().values
        if ("SW" in sats) addAll(listOf("SubEnable", "SubGain"))
        if ("LR" in sats || "RR" in sats) addAll(listOf("SurroundEnable", "SurroundLevel", "MusicSurroundLevel", "SurroundMode"))
        if (p.height) add("HeightChannelLevel")
    }

    private fun sound(player: Player, action: String, arg: (String) -> String): HttpResponse {
        if (visiblePlayers().none { it.uuid == player.uuid }) return fault(800)
        val values = soundValues.getOrPut(player.uuid) { mutableMapOf("Bass" to 0, "Treble" to 0, "Loudness" to 1) }
        fun eqDefault(t: String) = if (t == "SubEnable" || t == "SurroundEnable") 1 else 0
        fun range(t: String) = when (t) {
            "Bass", "Treble", "HeightChannelLevel" -> -10..10
            "SubGain", "SurroundLevel", "MusicSurroundLevel" -> -15..15
            "Trueplay" -> 0..1
            "AudioDelay" -> 0..5
            else -> 0..1
        }
        fun put(key: String, raw: String): HttpResponse {
            val v = raw.toIntOrNull()?.takeIf { it in range(key) } ?: return fault(402)
            values[key] = v
            return ok(SonosClient.RC_SERVICE, action)
        }
        return when (action) {
            "GetBass" -> ok(SonosClient.RC_SERVICE, action, mapOf("CurrentBass" to values.getValue("Bass").toString()))
            "GetTreble" -> ok(SonosClient.RC_SERVICE, action, mapOf("CurrentTreble" to values.getValue("Treble").toString()))
            "GetLoudness" -> ok(SonosClient.RC_SERVICE, action, mapOf("CurrentLoudness" to values.getValue("Loudness").toString()))
            "SetBass" -> put("Bass", arg("DesiredBass"))
            "SetTreble" -> put("Treble", arg("DesiredTreble"))
            "SetLoudness" -> put("Loudness", arg("DesiredLoudness"))
            "GetRoomCalibrationStatus" -> ok(
                SonosClient.RC_SERVICE, action,
                mapOf("RoomCalibrationEnabled" to (values["Trueplay"] ?: 1).toString(), "RoomCalibrationAvailable" to if (player.modelNumber == "S12") "0" else "1"),
            )
            "SetRoomCalibrationStatus" -> put("Trueplay", arg("RoomCalibrationEnabled"))
            "GetEQ" -> arg("EQType").takeIf { it in eqTypes(player) }
                ?.let { t -> ok(SonosClient.RC_SERVICE, action, mapOf("CurrentValue" to (values[t] ?: eqDefault(t)).toString())) } ?: fault(402)
            "SetEQ" -> arg("EQType").takeIf { it in eqTypes(player) }?.let { t -> put(t, arg("DesiredValue")) } ?: fault(402)
            else -> fault(401)
        }
    }

    /** Current value of a sound setting, for test assertions (Bass, Treble, Loudness or an EQ type). */
    fun soundValue(uuid: String, key: String): Int? = soundValues[uuid]?.get(key)

    fun zoneGroupState(): String = buildString {
        append("<ZoneGroupState><ZoneGroups>")
        val visible = visiblePlayers()
        visible.filter { coordinatorOf(it.uuid) == it.uuid }.forEachIndexed { i, c ->
            append("<ZoneGroup Coordinator=\"${c.uuid}\" ID=\"${c.uuid}:${100 + i}\">")
            (listOf(c) + visible.filter { it.uuid != c.uuid && coordinatorOf(it.uuid) == c.uuid }).forEach { appendPlayer(it) }
            append("</ZoneGroup>")
        }
        append("</ZoneGroups><VanishedDevices/></ZoneGroupState>")
    }

    private fun StringBuilder.appendPlayer(player: Player) {
        val p = player.copy(room = names[player.uuid] ?: player.room)
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
${if (p.lineIn) "      <service><serviceType>urn:schemas-upnp-org:service:AudioIn:1</serviceType><controlURL>/AudioIn/Control</controlURL><SCPDURL>/xml/AudioIn1.xml</SCPDURL></service>\n" else ""}      <service>
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
            Player(rincon(1), "Living Room", "Sonos Arc", "S19", "Arc", "192.168.50.11", soundbar = true, height = true),
            Player(rincon(2), "Kitchen", "Sonos One", "S18", "One", "192.168.50.12"),
            Player(rincon(3), "Bedroom", "Sonos Era 100", "S39", "Era 100", "192.168.50.13"),
            Player(rincon(4), "Study", "Sonos Play:1", "S12", "Play:1", "192.168.50.14"),
            Player(rincon(5), "Den", "Sonos Sub Mini", "S36", "Sub Mini", "192.168.50.15"),
            Player(rincon(6), "Office", "Sonos Five", "S33", "Five", "192.168.50.16", lineIn = true),
            Player(rincon(7), "Patio", "Sonos Era 300", "S41", "Era 300", "192.168.50.17"),
            Player(rincon(8), "Loft", "Sonos One SL", "S22", "One SL", "192.168.50.18"),
            Player(rincon(9), "Loft", "Sonos One SL", "S22", "One SL", "192.168.50.19"),
        )

        const val GRC_SERVICE = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
        const val GRC_CONTROL = "/MediaRenderer/GroupRenderingControl/Control"
        private val QUEUE_ACTIONS = setOf("RemoveAllTracksFromQueue", "RemoveTrackFromQueue", "Seek", "AddURIToQueue", "ConfigureSleepTimer", "GetRemainingSleepTimerDuration")

        private fun didlItem(title: String, cls: String) =
            "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item id=\"-1\" parentID=\"-1\" restricted=\"true\">" +
                "<dc:title>$title</dc:title><upnp:class>$cls</upnp:class></item></DIDL-Lite>"

        val DEFAULT_FAVORITES = listOf(
            SonosFavorite("FV:2/1", "Morning Radio", "x-sonosapi-stream:s0001?sid=254&flags=8224&sn=0", didlItem("Morning Radio", "object.item.audioItem.audioBroadcast"), "Radio station", null),
            SonosFavorite("FV:2/2", "Focus Playlist", "x-rincon-cpcontainer:1006206cplaylist%3atugma-focus?sid=9&flags=8300&sn=1", didlItem("Focus Playlist", "object.container.playlistContainer"), "Playlist", null),
            SonosFavorite("FV:2/3", "Rainy Day Jazz", "x-rincon-cpcontainer:1004206calbum%3atugma-jazz?sid=9&flags=8300&sn=1", didlItem("Rainy Day Jazz", "object.container.album.musicAlbum"), "Album", null),
        )
        private val SOUND_ACTIONS = setOf("GetRoomCalibrationStatus", "SetRoomCalibrationStatus", "GetBass", "SetBass", "GetTreble", "SetTreble", "GetLoudness", "SetLoudness", "GetEQ", "SetEQ")

        /** Loft starts as an existing pair so "Separate" can be tried straight away. */
        val DEFAULT_PAIRS = mapOf(rincon(8) to rincon(9))

        private val SCPD = """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>""" +
            listOf("AddBondedZones", "RemoveBondedZones", "CreateStereoPair", "SeparateStereoPair", "AddHTSatellite", "RemoveHTSatellite", "GetZoneInfo")
                .joinToString("") { "<action><name>$it</name></action>" } +
            "</actionList></scpd>"
    }
}

