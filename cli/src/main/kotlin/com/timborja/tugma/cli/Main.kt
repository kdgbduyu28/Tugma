package com.timborja.tugma.cli

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.InMemoryCommandLog
import com.timborja.tugma.sonos.Endpoint
import com.timborja.tugma.sonos.HomeTheaterRules
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.HtSlot
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import com.timborja.tugma.sonos.SonosClient
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

Writes (each one shows the exact SOAP and asks you to type "yes"):
  pair <left-ip> <right-ip>
  unpair <ip>
  ht <bar-ip> [--lr ip] [--rr ip] [--sw ip] [--lf ip] [--rf ip]
  ht-remove <bar-ip> <satellite-ip>
  raw <ip> <ZoneGroupTopology|DeviceProperties|...> <Action> [Arg=value ...]

Options:
  --dry-run    print the plan, send nothing
  --force      let rule violations through (still asks for confirmation)
  --verbose    print every HTTP request and response
  --demo       use the built-in fakes (Sonos 192.168.50.11-19, DLNA .31-34, Cast .41-43 and group .41:32187)"""

private class Args(raw: List<String>) {
    val flags = raw.filter { it.startsWith("--") && it !in valued }.toSet()
    val options = buildMap {
        raw.forEachIndexed { i, a -> if (a in valued) raw.getOrNull(i + 1)?.let { put(a, it) } }
    }
    val positional = raw.filterIndexed { i, a -> !a.startsWith("--") && raw.getOrNull(i - 1) !in valued }

    companion object {
        val valued = setOf("--lr", "--rr", "--sw", "--lf", "--rf")
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
    val cli = Cli(args, client, household, transport, dlnaClient, dlnaDiscoverer, castClient)

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
        "renderers" -> renderers()
        "renderer-status" -> rendererStatus(rest)
        "renderer-volume" -> rendererVolume(rest)
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
