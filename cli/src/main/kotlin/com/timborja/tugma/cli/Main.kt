package com.timborja.tugma.cli

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.InMemoryCommandLog
import com.timborja.tugma.sonos.Endpoint
import com.timborja.tugma.sonos.HomeTheaterRules
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.HtSlot
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import com.timborja.tugma.core.Station
import com.timborja.tugma.core.UrlPlayback
import com.timborja.tugma.sonos.Recurrence
import com.timborja.tugma.sonos.SonosAlarm
import com.timborja.tugma.sonos.SonosClient
import com.timborja.tugma.sonos.SonosSleepTimer
import com.timborja.tugma.sonos.SonosGroupPlanner
import com.timborja.tugma.sonos.SonosGroupRules
import com.timborja.tugma.sonos.SoundSetting
import com.timborja.tugma.sonos.roomGroups
import com.timborja.tugma.sonos.SonosHousehold
import com.timborja.tugma.sonos.SonosPlanner
import com.timborja.tugma.sonos.SonosSpeaker
import com.timborja.tugma.sonos.StereoPairRules
import com.timborja.tugma.sonos.demo.FakeSonosHousehold
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.LanOnlyTransport
import com.timborja.tugma.upnp.LoggingTransport
import com.timborja.tugma.upnp.OkHttpTransport
import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.SsdpDiscoverer
import com.timborja.tugma.cast.CastClient
import com.timborja.tugma.cast.CastDevice
import com.timborja.tugma.cast.CastPlayback
import com.timborja.tugma.cast.TlsCastConnector
import com.timborja.tugma.cast.demo.FakeCastHousehold
import com.timborja.tugma.linkplay.LinkplayChannel
import com.timborja.tugma.linkplay.LinkplayClient
import com.timborja.tugma.linkplay.LinkplayDevice
import com.timborja.tugma.linkplay.LinkplayExtras
import com.timborja.tugma.linkplay.LinkplayHttp
import com.timborja.tugma.linkplay.LinkplayPlanner
import com.timborja.tugma.linkplay.demo.FakeLinkplayHousehold
import com.timborja.tugma.upnp.demo.FakeDlnaHousehold
import com.timborja.tugma.upnp.dlna.DlnaClient
import com.timborja.tugma.upnp.dlna.UpnpServices
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val USAGE = """tugma: test Tugma's Sonos commands against real speakers.

Read-only:
  discover                         SSDP search, then list every speaker
  topology <ip>                    roles and groups as seen from <ip>
  topology-xml <ip>                raw ZoneGroupState XML (save it as a test fixture)
  describe <ip>                    device_description.xml summary
  actions <ip>                     DeviceProperties actions this firmware declares

Other brands (UPnP/DLNA MediaRenderers):
  renderers                        SSDP search for MediaRenderers (Sonos ones are marked)
  renderer-status <location-url>   volume, mute, transport and track via RenderingControl/AVTransport
  renderer-volume <location-url> <n>   set volume (no confirmation: it doesn't change any setup)

Google Cast (by IP; port defaults to 8009, groups use their own port, e.g. 192.168.1.40:32187):
  cast-status <ip[:port]>          receiver volume, running app and media state
  cast-volume <ip[:port]> <0-100>
  cast-mute <ip[:port]> on|off
  cast-play|cast-pause|cast-stop <ip[:port]>
  cast-play-url <ip[:port]> <url> [--title t] [--file]   via Google's Default Media Receiver

Linkplay (Arylic, WiiM, Audio Pro…; writes show the exact URLs and ask for "yes"):
  lp-status <ip>                   device, channel, playback and group guests
  lp-pair <left-ip> <right-ip>     group across models, then split left/right (experimental)
  lp-group <host-ip> <guest-ip>…   multiroom group
  lp-kick <host-ip> <guest-ip>
  lp-ungroup <host-ip>
  lp-channel <host-ip> <guest-ip|self> <stereo|left|right>   (experimental)
  lp-play-url <ip> <url>           play a link (setPlayerCmd:play)
  lp-rename <ip> <name>
  lp-presets <ip> [n]              list presets (if the firmware names them) or play preset n
  lp-eq <ip> [on|off|<preset>]     the speaker's own EQ (WiiM API, experimental)

Writes (each one shows the exact SOAP and asks you to type "yes"):
  pair <left-ip> <right-ip>
  unpair <ip>
  ht <bar-ip> [--lr ip] [--rr ip] [--sw ip] [--lf ip] [--rf ip]
  ht-remove <bar-ip> <satellite-ip>
  groups <ip>                      multiroom groups and group volume
  group <coordinator-ip> <member-ip>…   members play along with the coordinator
  leave <ip>…                      rooms leave their groups
  group-volume <coordinator-ip> [0-100]  read or set the whole group's volume (no confirmation)
  sound <ip> [bass=N treble=N loudness=on|off night=on|off dialog=on|off sub=N surround=N height=N delay=0-5 surround-full=on|off reset]
                                   read or set sound settings (no confirmation, like volume)
  favorites <ip>                   list My Sonos favorites
  play-favorite <ip> <number|title>   play a favorite on <ip>'s group (containers replace the queue)
  queue <ip> [play N | remove N | clear]   the group's queue
  line-in <ip> <source-ip>         play <source-ip>'s line-in on <ip>'s group
  tv <bar-ip>                      switch a soundbar to its TV input
  play-url <ip> <url> [--title t] [--file]   play a radio stream (or with --file, an audio file) on <ip>'s group
  settings <ip> [light=on|off] [touch=on|off] [trueplay=on|off] [name=<room name>]
  sleep <ip> [minutes|off]         read, set or cancel the group's sleep timer
  alarms <ip>                      list alarms
  alarm-add <ip> <HH:MM> [--room ip] [--days 12345|daily|weekdays|weekends|once] [--volume n]
  alarm-delete <ip> <id>
  raw <ip> <ZoneGroupTopology|DeviceProperties|...> <Action> [Arg=value ...]

Options:
  --dry-run    print the plan, send nothing
  --force      let rule violations through (still asks for confirmation)
  --verbose    print every HTTP request and response
  --demo       use the built-in fakes (Sonos 192.168.50.11-19, DLNA .31-34, Cast .41-43 and group .41:32187, Linkplay .51-53)"""

private class Args(raw: List<String>) {
    val flags = raw.filter { it.startsWith("--") && it !in valued }.toSet()
    val options = buildMap {
        raw.forEachIndexed { i, a -> if (a in valued) raw.getOrNull(i + 1)?.let { put(a, it) } }
    }
    val positional = raw.filterIndexed { i, a -> !a.startsWith("--") && raw.getOrNull(i - 1) !in valued }

    companion object {
        val valued = setOf("--lr", "--rr", "--sw", "--lf", "--rf", "--room", "--days", "--volume", "--title")
    }
}

fun main(argv: Array<String>) {
    val args = Args(argv.toList())
    val cmd = args.positional.firstOrNull()
    if (cmd == null || cmd == "help") {
        println(USAGE)
        return
    }
    val log = InMemoryCommandLog()
    val demo = "--demo" in args.flags
    val fake = if (demo) FakeSonosHousehold() else null
    val fakeDlna = if (demo) FakeDlnaHousehold() else null
    val base: HttpTransport = fake ?: LanOnlyTransport(OkHttpTransport())
    val transport = LoggingTransport(base, log)
    val dlnaTransport = LoggingTransport(fakeDlna ?: base, log)
    val discoverer: Discoverer = fake ?: SsdpDiscoverer(SonosClient.ZONE_PLAYER_ST)
    val client = SonosClient(transport)
    val household = SonosHousehold(client, discoverer)
    val dlnaClient = DlnaClient(dlnaTransport)
    val dlnaDiscoverer: Discoverer = fakeDlna ?: SsdpDiscoverer(UpnpServices.MEDIA_RENDERER_ST)
    val castClient = CastClient(if (demo) FakeCastHousehold() else TlsCastConnector(), log)
    val lpTransport = LoggingTransport(if (demo) FakeLinkplayHousehold() else LanOnlyTransport(OkHttpTransport(LinkplayHttp.lanClient())), log)
    val cli = Cli(args, client, household, transport, dlnaClient, dlnaDiscoverer, castClient, LinkplayClient(lpTransport))

    val code = try {
        runBlocking { cli.run(cmd, args.positional.drop(1)) }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        1
    } finally {
        if ("--verbose" in args.flags) log.entries.value.reversed().forEach { println(it.toText()) }
    }
    exitProcess(code)
}

private class Cli(
    private val args: Args,
    private val client: SonosClient,
    private val household: SonosHousehold,
    private val transport: HttpTransport,
    private val dlna: DlnaClient,
    private val dlnaDiscoverer: Discoverer,
    private val cast: CastClient,
    private val linkplay: LinkplayClient,
) {
    private val dryRun = "--dry-run" in args.flags
    private val force = "--force" in args.flags

    suspend fun run(cmd: String, rest: List<String>): Int = when (cmd) {
        "discover" -> {
            val endpoints = household.discover()
            if (endpoints.isEmpty()) {
                println("No speakers answered the SSDP search. Try: topology <ip>")
                1
            } else {
                printHousehold(household.load(endpoints))
                0
            }
        }
        "topology" -> { printHousehold(load(rest)); 0 }
        "topology-xml" -> { println(client.topology(endpoint(rest, 0)).rawXml); 0 }
        "describe" -> { println(client.describe(endpoint(rest, 0))); 0 }
        "actions" -> { client.devicePropertiesActions(endpoint(rest, 0)).sorted().forEach(::println); 0 }
        "pair" -> pair(rest)
        "unpair" -> unpair(rest)
        "ht" -> homeTheater(rest)
        "ht-remove" -> htRemove(rest)
        "raw" -> raw(rest)
        "groups" -> groups(rest)
        "group" -> group(rest)
        "leave" -> leave(rest)
        "group-volume" -> groupVolume(rest)
        "sound" -> sound(rest)
        "favorites" -> favorites(rest)
        "play-favorite" -> playFavorite(rest)
        "sleep" -> sleep(rest)
        "settings" -> settings(rest)
        "lp-rename" -> { LinkplayExtras(linkplay, lp(rest, 0)).rename(rest.drop(1).joinToString(" ")); println("renamed"); 0 }
        "play-url" -> {
            val state = load(rest)
            val room = state.at(endpoint(rest, 0))
            val coord = state.coordinatorOf(room.uuid) ?: room
            playUrl(client.playback(room.endpoint, coord.endpoint), rest)
        }
        "cast-play-url" -> playUrl(castPlayback(rest), rest)
        "lp-play-url" -> playUrl(linkplay.playback(lp(rest, 0)) as UrlPlayback, rest)
        "queue" -> queue(rest)
        "line-in" -> lineIn(rest)
        "tv" -> tv(rest)
        "alarms" -> alarms(rest)
        "alarm-add" -> alarmAdd(rest)
        "alarm-delete" -> alarmDelete(rest)
        "renderers" -> renderers()
        "renderer-status" -> rendererStatus(rest)
        "renderer-volume" -> rendererVolume(rest)
        "lp-status" -> lpStatus(rest)
        "lp-pair" -> lpRun(LinkplayPlanner.stereoPair(lp(rest, 0), lp(rest, 1)))
        "lp-group" -> lpRun(LinkplayPlanner.group(lp(rest, 0), rest.drop(1).indices.map { lp(rest, it + 1) }))
        "lp-kick" -> lp(rest, 0).let { h -> lpRun(LinkplayPlanner.kick(h, ip(rest, 1), ip(rest, 1))) }
        "lp-ungroup" -> lpRun(LinkplayPlanner.ungroup(lp(rest, 0)))
        "lp-channel" -> lpChannel(rest)
        "lp-presets" -> lpPresets(rest)
        "lp-eq" -> lpEq(rest)
        "cast-status" -> castStatus(rest)
        "cast-volume" -> castDo(rest) { setVolume(rest.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("missing volume")) }
        "cast-mute" -> castDo(rest) { setMuted(rest.getOrNull(1)?.lowercase() in setOf("on", "true", "1")) }
        "cast-play" -> castDo(rest) { play() }
        "cast-pause" -> castDo(rest) { pause() }
        "cast-stop" -> castDo(rest) { stop() }
        else -> { println(USAGE); 2 }
    }

    private fun endpoint(rest: List<String>, i: Int): Endpoint {
        val v = rest.getOrNull(i) ?: throw IllegalArgumentException("missing <ip>")
        val host = v.substringBefore(':')
        val port = v.substringAfter(':', "").toIntOrNull() ?: SonosClient.DEFAULT_PORT
        return Endpoint(host, port)
    }

    private suspend fun load(rest: List<String>): HouseholdState = household.load(listOf(endpoint(rest, 0)))

    private fun HouseholdState.at(e: Endpoint): SonosSpeaker =
        speakers.firstOrNull { it.host == e.host && it.port == e.port }
            ?: throw IllegalArgumentException("$e is not in this household")

    private suspend fun pair(rest: List<String>): Int {
        val state = load(rest)
        val left = state.at(endpoint(rest, 0))
        val right = state.at(endpoint(rest, 1))
        val issues = StereoPairRules.check(left, right, state)
        val legacy = SonosPlanner.useLegacyPairing(runCatching { client.devicePropertiesActions(left.endpoint) }.getOrNull())
        return confirmAndRun(SonosPlanner.stereoPair(left, right, legacy, issues.texts()), issues, state)
    }

    private suspend fun unpair(rest: List<String>): Int {
        val state = load(rest)
        val target = state.at(endpoint(rest, 0))
        val legacy = SonosPlanner.useLegacyPairing(runCatching { client.devicePropertiesActions(target.endpoint) }.getOrNull())
        val map = state.topology.member(target.uuid)?.channelMapSet
        return confirmAndRun(SonosPlanner.separateStereoPair(target, map, legacy), emptyList(), state)
    }

    private suspend fun homeTheater(rest: List<String>): Int {
        val state = load(rest)
        val bar = state.at(endpoint(rest, 0))
        val slots = HomeTheaterRules.existing(bar, state).toMutableMap()
        mapOf("--lr" to HtSlot.LEFT_SURROUND, "--rr" to HtSlot.RIGHT_SURROUND, "--sw" to HtSlot.SUB, "--lf" to HtSlot.FRONT_LEFT, "--rf" to HtSlot.FRONT_RIGHT)
            .forEach { (flag, slot) -> args.options[flag]?.let { slots[slot] = state.at(endpoint(listOf(it), 0)) } }
        val issues = HomeTheaterRules.check(bar, slots, state, allowExperimental = true)
        val plan = SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, slots), HomeTheaterRules.summary(bar, slots), issues.texts())
        return confirmAndRun(plan, issues, state)
    }

    private suspend fun htRemove(rest: List<String>): Int {
        val state = load(rest)
        val bar = state.at(endpoint(rest, 0))
        val sat = state.at(endpoint(rest, 1))
        return confirmAndRun(SonosPlanner.removeHtSatellite(bar, sat.uuid, sat.roomName), emptyList(), state)
    }

    private suspend fun groups(rest: List<String>): Int {
        val state = load(rest)
        for (g in state.roomGroups()) {
            if (g.members.isEmpty()) { println("${g.coordinator.roomName.padEnd(18)} ${g.coordinator.endpoint}  standalone"); continue }
            val v = if (g.members.isEmpty()) null else runCatching { client.groupVolume(g.coordinator.endpoint).volume() }.getOrNull()
            println("${g.name}${v?.let { "  (group volume $it)" } ?: ""}")
            g.all.forEach { println("  ${it.roomName.padEnd(16)} ${it.endpoint}${if (it.uuid == g.coordinator.uuid) "  leads" else ""}") }
        }
        return 0
    }

    private suspend fun group(rest: List<String>): Int {
        val state = load(rest)
        val coordinator = state.at(endpoint(rest, 0))
        val members = rest.indices.drop(1).map { state.at(endpoint(rest, it)) }
        val issues = SonosGroupRules.check(coordinator, members, state)
        return confirmAndRun(SonosGroupPlanner.join(coordinator, members, state, issues.texts()), issues, state)
    }

    private suspend fun leave(rest: List<String>): Int {
        val state = load(rest)
        val members = rest.indices.map { state.at(endpoint(rest, it)) }
        return confirmAndRun(SonosGroupPlanner.leave(members), emptyList(), state)
    }

    private suspend fun groupVolume(rest: List<String>): Int {
        val gv = client.groupVolume(endpoint(rest, 0))
        rest.getOrNull(1)?.let { v -> gv.setVolume(v.toIntOrNull() ?: throw IllegalArgumentException("volume must be 0-100")) }
        println("group volume ${gv.volume() ?: "?"}, muted ${gv.muted() ?: "?"}")
        return 0
    }

    private suspend fun sound(rest: List<String>): Int {
        val e = endpoint(rest, 0)
        val sound = client.sound(e)
        val keys = mapOf(
            "bass" to SoundSetting.BASS, "treble" to SoundSetting.TREBLE, "loudness" to SoundSetting.LOUDNESS,
            "night" to SoundSetting.NIGHT_MODE, "dialog" to SoundSetting.DIALOG, "sub" to SoundSetting.SUB_GAIN,
            "sub-on" to SoundSetting.SUB_ENABLED, "surround" to SoundSetting.SURROUND_LEVEL,
            "music-surround" to SoundSetting.MUSIC_SURROUND_LEVEL, "surround-on" to SoundSetting.SURROUND_ENABLED,
            "height" to SoundSetting.HEIGHT_LEVEL,
            "delay" to SoundSetting.AUDIO_DELAY, "surround-full" to SoundSetting.SURROUND_FULL_VOLUME,
        )
        for (a in rest.drop(1)) {
            if (a == "reset") { sound.reset(sound.read(homeTheater = true)); continue }
            val setting = keys[a.substringBefore('=')] ?: throw IllegalArgumentException("unknown setting \"$a\" (try ${keys.keys.joinToString()})")
            val raw = a.substringAfter('=', "")
            val value = when (raw.lowercase()) { "on", "true" -> 1; "off", "false" -> 0; else -> raw.toIntOrNull() ?: throw IllegalArgumentException("bad value in \"$a\"") }
            sound.set(setting, value)
        }
        val state = sound.read(homeTheater = true)
        state.supported.forEach { s ->
            val v = state[s]!!
            println("${s.label.padEnd(24)} ${if (s.isSwitch) (if (v != 0) "on" else "off") else "%+d".format(v)}")
        }
        return 0
    }

    private suspend fun favorites(rest: List<String>): Int {
        client.favorites(endpoint(rest, 0)).list().forEachIndexed { i, f ->
            println("${(i + 1).toString().padStart(2)}. ${f.title}${f.description?.let { "  ($it)" } ?: ""}${if (f.needsQueue) "  [queue]" else ""}")
        }
        return 0
    }

    private suspend fun playFavorite(rest: List<String>): Int {
        val state = load(rest)
        val room = state.at(endpoint(rest, 0))
        val coord = state.coordinatorOf(room.uuid) ?: room
        val favs = client.favorites(room.endpoint).list()
        val key = rest.drop(1).joinToString(" ").ifBlank { throw IllegalArgumentException("missing favorite number or title") }
        val f = key.toIntOrNull()?.let { favs.getOrNull(it - 1) } ?: favs.firstOrNull { it.title.equals(key, ignoreCase = true) }
            ?: throw IllegalArgumentException("no favorite \"$key\" (run: favorites ${rest[0]})")
        client.player(coord.endpoint, coord.uuid).play(f)
        println("Playing ${f.title} on ${coord.roomName}'s group")
        return 0
    }

    private suspend fun queue(rest: List<String>): Int {
        val state = load(rest)
        val coord = state.coordinatorOf(state.at(endpoint(rest, 0)).uuid)!!
        val q = client.queue(coord.endpoint, coord.uuid)
        val n = rest.getOrNull(2)?.toIntOrNull()
        when (rest.getOrNull(1)) {
            null -> {}
            "play" -> q.playFrom(n ?: throw IllegalArgumentException("queue play <N>"))
            "remove" -> q.remove(n ?: throw IllegalArgumentException("queue remove <N>"))
            "clear" -> q.clear()
            else -> throw IllegalArgumentException("queue [play N | remove N | clear]")
        }
        val items = q.list()
        if (items.isEmpty()) println("queue is empty")
        items.forEach { println("${it.position.toString().padStart(3)}. ${it.title}${it.artist?.let { a -> " — $a" } ?: ""}") }
        return 0
    }

    private suspend fun lineIn(rest: List<String>): Int {
        val state = load(rest)
        val coord = state.coordinatorOf(state.at(endpoint(rest, 0)).uuid)!!
        val source = state.at(endpoint(rest, 1))
        client.inputs(coord.endpoint).lineIn(source)
        println("${coord.roomName} is playing ${source.roomName}'s line-in")
        return 0
    }

    private suspend fun tv(rest: List<String>): Int {
        val state = load(rest)
        val bar = state.at(endpoint(rest, 0))
        client.inputs(bar.endpoint).tv(bar)
        println("${bar.roomName} switched to TV")
        return 0
    }

    private suspend fun playUrl(target: UrlPlayback, rest: List<String>): Int {
        val url = rest.getOrNull(1) ?: throw IllegalArgumentException("missing <url>")
        val station = Station(args.options["--title"] ?: url.substringAfter("://").substringBefore('/'), url, live = "--file" !in args.flags)
        require(station.isValidUrl) { "url must start with http:// or https://" }
        target.playUrl(station.url, station.name, station.live)
        println("playing ${station.name}")
        return 0
    }

    private suspend fun settings(rest: List<String>): Int {
        val s = client.settings(endpoint(rest, 0))
        fun onOff(v: String) = when (v.lowercase()) { "on", "true", "1" -> true; "off", "false", "0" -> false; else -> throw IllegalArgumentException("use on or off") }
        val changes = rest.drop(1).joinToString(" ").split(Regex("\\s+(?=\\w+=)")).filter { it.contains('=') }
        for (c in changes) {
            val (k, v) = c.substringBefore('=') to c.substringAfter('=')
            when (k) {
                "light" -> s.setStatusLight(onOff(v))
                "touch" -> s.setButtonsLocked(!onOff(v))
                "trueplay" -> s.setTrueplay(onOff(v))
                "name" -> s.rename(v)
                else -> throw IllegalArgumentException("unknown setting $k")
            }
        }
        val r = s.read()
        println("room ${r.roomName ?: "?"} · status light ${r.statusLight.onOff()} · touch controls ${r.buttonsLocked?.not().onOff()} · Trueplay ${if (r.trueplayAvailable == false) "not tuned" else r.trueplayEnabled.onOff()}")
        return 0
    }

    private fun Boolean?.onOff() = when (this) { true -> "on"; false -> "off"; null -> "?" }

    private suspend fun sleep(rest: List<String>): Int {
        val state = load(rest)
        val coord = state.coordinatorOf(state.at(endpoint(rest, 0)).uuid)!!
        val timer = client.sleepTimer(coord.endpoint)
        rest.getOrNull(1)?.let { v -> timer.set(if (v == "off") null else v.toIntOrNull() ?: throw IllegalArgumentException("minutes or off")) }
        println(timer.remainingSeconds()?.let { "sleep timer: ${SonosSleepTimer.format(it)} left" } ?: "sleep timer: off")
        return 0
    }

    private suspend fun alarms(rest: List<String>): Int {
        val state = load(rest)
        val list = client.alarms(endpoint(rest, 0)).list()
        if (list.isEmpty()) println("no alarms")
        list.forEach { a ->
            println("#${a.id} ${a.shortTime} ${Recurrence.label(a.recurrence).padEnd(14)} ${state.nameOf(a.roomUuid).padEnd(14)} vol ${a.volume} ${if (a.enabled) "on" else "off"}${if (a.isChime) "" else "  (music)"}")
        }
        return 0
    }

    private suspend fun alarmAdd(rest: List<String>): Int {
        val state = load(rest)
        val (h, m) = (rest.getOrNull(1) ?: throw IllegalArgumentException("missing HH:MM")).split(':').map { it.toIntOrNull() ?: throw IllegalArgumentException("time must be HH:MM") }
        val room = args.options["--room"]?.let { state.at(endpoint(listOf(it), 0)) } ?: state.at(endpoint(rest, 0))
        val days = when (val d = args.options["--days"] ?: "daily") {
            "daily" -> Recurrence.DAILY; "weekdays" -> Recurrence.WEEKDAYS; "weekends" -> Recurrence.WEEKENDS; "once" -> Recurrence.ONCE
            else -> Recurrence.of(d.map { it.digitToIntOrNull() ?: throw IllegalArgumentException("--days digits are 0 (Sun) to 6 (Sat)") }.toSet())
        }
        val alarm = SonosAlarm(startTime = SonosAlarm.time(h, m), recurrence = days, roomUuid = room.uuid, volume = args.options["--volume"]?.toIntOrNull() ?: 20)
        val id = client.alarms(endpoint(rest, 0)).create(alarm)
        println("added alarm #$id: ${alarm.shortTime} ${Recurrence.label(days)} in ${room.roomName}")
        return 0
    }

    private suspend fun alarmDelete(rest: List<String>): Int {
        val id = rest.getOrNull(1) ?: throw IllegalArgumentException("missing alarm id")
        if (!dryRun && !ask()) return 3
        client.alarms(endpoint(rest, 0)).delete(id)
        println("deleted alarm #$id")
        return 0
    }

    private suspend fun raw(rest: List<String>): Int {
        val e = endpoint(rest, 0)
        val service = rest.getOrNull(1) ?: throw IllegalArgumentException("missing service")
        val action = rest.getOrNull(2) ?: throw IllegalArgumentException("missing action")
        val callArgs = rest.drop(3).map { it.substringBefore('=') to it.substringAfter('=', "") }
        val call = SoapCall(e.baseUrl, "/$service/Control", "urn:schemas-upnp-org:service:$service:1", action, callArgs)
        val readOnly = action.startsWith("Get")
        println("POST ${call.url}\nSOAPACTION: \"${call.soapAction}\"\n${call.envelope()}\n")
        if (dryRun) return 0
        if (!readOnly && !ask()) return 3
        SoapClient(transport).call(call).forEach { (k, v) -> println("$k = $v") }
        return 0
    }

    private suspend fun confirmAndRun(plan: CommandPlan, issues: List<Issue>, state: HouseholdState): Int {
        println("== ${plan.title}")
        println(plan.summary)
        issues.forEach { println("  [${it.severity}] ${it.message}") }
        plan.requests.forEachIndexed { i, r ->
            println("\n-- step ${i + 1}: ${r.label}\n${r.method} ${r.url}")
            r.headers.forEach { (k, v) -> println("$k: $v") }
            println(r.body)
        }
        if (issues.any { it.severity == Severity.BLOCK } && !force) {
            println("\nBlocked by the rules above. Re-run with --force to send anyway.")
            return 4
        }
        if (dryRun) {
            println("\n(dry run, nothing sent)")
            return 0
        }
        if (!ask()) return 3
        client.execute(plan.confirm())
        println("\nDone. Topology now:")
        printHousehold(household.refresh(state))
        return 0
    }

    private suspend fun renderers(): Int {
        val found = coroutineScope {
            dlnaDiscoverer.search().map { it.location }.distinct()
                .map { loc -> async { loc to runCatching { dlna.describe(loc) } } }.awaitAll()
        }
        if (found.isEmpty()) {
            println("No MediaRenderers answered.")
            return 1
        }
        for ((loc, result) in found) {
            val r = result.getOrNull()
            when {
                result.isFailure -> println("  $loc\n    ! ${result.exceptionOrNull()?.message}")
                r == null -> println("  $loc\n    (no RenderingControl/AVTransport)")
                else -> {
                    println("  ${r.friendlyName}  [${r.manufacturer ?: "?"} ${r.modelName ?: ""}]${if (r.isSonos) "  (Sonos: use the Sonos commands)" else ""}")
                    println("    $loc")
                    println("    RenderingControl: ${r.renderingControl?.controlUrl ?: "-"}  (volume 0-${r.maxVolume})")
                    println("    AVTransport:      ${r.avTransport?.controlUrl ?: "-"}")
                }
            }
        }
        return 0
    }

    private suspend fun renderer(rest: List<String>) =
        dlna.describe(rest.getOrNull(0) ?: throw IllegalArgumentException("missing <location-url>"))
            ?: throw IllegalArgumentException("That device has no RenderingControl or AVTransport service")

    private suspend fun rendererStatus(rest: List<String>): Int {
        val r = renderer(rest)
        val s = dlna.playback(r).status()
        println("${r.friendlyName}: volume ${s.volume ?: "?"}/${s.maxVolume}, muted ${s.muted ?: "?"}, ${s.transport}")
        if (s.title != null || s.artist != null) println("  ${s.title ?: ""} — ${s.artist ?: ""}")
        return 0
    }

    private suspend fun rendererVolume(rest: List<String>): Int {
        val r = renderer(rest)
        val v = rest.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("missing volume")
        val control = dlna.playback(r)
        control.setVolume(v)
        println("${r.friendlyName}: volume now ${control.status().volume}")
        return 0
    }

    private fun ip(rest: List<String>, i: Int) = rest.getOrNull(i) ?: throw IllegalArgumentException("missing <ip>")

    private suspend fun lp(rest: List<String>, i: Int): LinkplayDevice {
        val host = ip(rest, i)
        val d = linkplay.probe(host) ?: throw IllegalArgumentException("$host didn't answer as a Linkplay speaker")
        return if (d.isGuest) d else d.copy(guests = runCatching { linkplay.guests(d) }.getOrDefault(emptyList()))
    }

    private suspend fun lpStatus(rest: List<String>): Int {
        val d = lp(rest, 0)
        val p = linkplay.playback(d).status()
        println("${d.name}  [${d.hardware ?: "?"}, firmware ${d.firmware ?: "?"}]  ${if (d.https) "https" else "http"}  uuid ${d.uuid}")
        println("  ${if (d.isGuest) "guest of ${d.masterUuid}" else "host/standalone"} · volume ${p.volume ?: "?"} · muted ${p.muted ?: "?"} · ${p.transport}")
        p.title?.let { println("  $it${p.artist?.let { a -> " — $a" } ?: ""}") }
        d.guests.forEach { g -> println("  guest ${g.name} ${g.ip} channel=${g.channel.label.lowercase()} volume=${g.volume ?: "?"}") }
        return 0
    }

    private suspend fun lpPresets(rest: List<String>): Int {
        val x = LinkplayExtras(linkplay, lp(rest, 0))
        rest.getOrNull(1)?.let { n -> x.playPreset(n.toIntOrNull() ?: throw IllegalArgumentException("preset number 1-10")); println("playing preset $n") }
        val list = x.presets()
        if (list == null) println("this firmware doesn't list preset names (numbers 1-${LinkplayExtras.MAX_PRESETS} may still play)")
        list?.forEach { println("${it.number.toString().padStart(2)}. ${it.name}${it.source?.let { s -> "  ($s)" } ?: ""}") }
        return 0
    }

    private suspend fun lpEq(rest: List<String>): Int {
        val x = LinkplayExtras(linkplay, lp(rest, 0))
        when (val v = rest.getOrNull(1)) {
            null -> {}
            "on", "off" -> x.setEq(v == "on")
            else -> x.loadEq(rest.drop(1).joinToString(" "))
        }
        val on = x.eqEnabled() ?: run { println("no EQ commands on this firmware"); return 0 }
        println("EQ ${if (on) "on" else "off"}; presets: ${x.eqPresets().joinToString()}")
        return 0
    }

    private suspend fun lpChannel(rest: List<String>): Int {
        val host = lp(rest, 0)
        val who = ip(rest, 1)
        val ch = LinkplayChannel.entries.firstOrNull { it.name.equals(rest.getOrNull(2), ignoreCase = true) }
            ?: throw IllegalArgumentException("channel must be stereo, left or right")
        val guest = host.guests.firstOrNull { it.ip == who }
        if (who != "self" && guest == null) throw IllegalArgumentException("$who isn't a guest of ${host.name}")
        return lpRun(LinkplayPlanner.setChannel(host, host, guest, ch))
    }

    private suspend fun lpRun(plan: CommandPlan): Int {
        println("== ${plan.title}\n${plan.summary}")
        plan.warnings.forEach { println("  [WARN] $it") }
        plan.requests.forEachIndexed { i, r -> println("-- step ${i + 1}: ${r.label}\n${r.method} ${r.url}") }
        if (dryRun) { println("\n(dry run, nothing sent)"); return 0 }
        if (!ask()) return 3
        linkplay.execute(plan.confirm())
        println("\nDone.")
        return 0
    }

    private fun castPlayback(rest: List<String>): CastPlayback {
        val v = rest.getOrNull(0) ?: throw IllegalArgumentException("missing <ip>")
        return CastPlayback(cast, v.substringBefore(':'), v.substringAfter(':', "").toIntOrNull() ?: CastDevice.DEFAULT_PORT)
    }

    private suspend fun castStatus(rest: List<String>): Int {
        val p = castPlayback(rest)
        val s = p.status()
        println("volume ${s.volume ?: "?"}/100, muted ${s.muted ?: "?"}, ${s.transport}")
        if (s.title != null || s.artist != null) println("  ${s.title ?: ""}${s.artist?.let { " — $it" } ?: ""}")
        return 0
    }

    private suspend fun castDo(rest: List<String>, block: suspend CastPlayback.() -> Unit): Int {
        val p = castPlayback(rest)
        p.block()
        return castStatus(rest)
    }

    private fun ask(): Boolean {
        print("\nSend this to your speakers? Type yes to continue: ")
        return readlnOrNull()?.trim()?.lowercase() == "yes"
    }

    private fun List<Issue>.texts() = filter { it.severity != Severity.BLOCK }.map { it.message }

    private fun printHousehold(state: HouseholdState) {
        println("Topology from ${state.source}:")
        for (s in state.speakers) {
            val flags = listOfNotNull("soundbar".takeIf { s.isSoundbar }, "sub".takeIf { s.isSub }, "hidden".takeIf { s.invisible })
            println(
                "  %-16s %-18s %-22s %s%s".format(
                    s.endpoint.toString(), s.roomName, s.modelName ?: "?", s.uuid,
                    if (flags.isEmpty()) "" else "  [" + flags.joinToString(",") + "]",
                ),
            )
            s.roleLabel?.let { println("  ${" ".repeat(16)} └ $it") }
            s.describeError?.let { println("  ${" ".repeat(16)} ! $it") }
        }
    }
}
