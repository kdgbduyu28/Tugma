package com.timborja.tugma.linkplay

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.ConfirmedPlan
import com.timborja.tugma.core.PlannedRequest
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.UrlPlayback
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.SpeakerSummary
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** 0 = stereo, 1 = left, 2 = right, as reported in getPlayerStatus `ch` and getSlaveList `channel`. */
enum class LinkplayChannel(val code: Int, val label: String) {
    STEREO(0, "Stereo"), LEFT(1, "Left"), RIGHT(2, "Right");
    companion object { fun of(v: Int?) = entries.firstOrNull { it.code == v } ?: STEREO }
}

data class LinkplayGuest(val name: String, val uuid: String?, val ip: String, val channel: LinkplayChannel, val volume: Int?, val muted: Boolean?)

data class LinkplayDevice(
    val uuid: String,
    val name: String,
    val host: String,
    val https: Boolean,
    val firmware: String?,
    val hardware: String?,
    /** "0" in getStatusEx means host or standalone; anything else means it's a guest. */
    val isGuest: Boolean,
    val masterUuid: String?,
    val guests: List<LinkplayGuest> = emptyList(),
    val channel: LinkplayChannel = LinkplayChannel.STEREO,
) {
    val roleLabel: String?
        get() = when {
            isGuest -> "Guest in a multiroom group"
            guests.isNotEmpty() -> "Group host · " + guests.joinToString(", ") { "${it.name} (${it.channel.label.lowercase()})" }
            channel != LinkplayChannel.STEREO -> "Plays ${channel.label.lowercase()} channel"
            else -> null
        }

    fun toSummary(): SpeakerSummary = SpeakerSummary(
        id = uuid,
        brand = Brand.LINKPLAY,
        name = name,
        model = hardware.orEmpty(),
        host = host,
        port = if (https) 443 else 80,
        capabilities = setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT, Capability.GROUP, Capability.CHANNEL_ASSIGN),
        roleLabel = roleLabel,
        manufacturer = "Linkplay",
        hidden = isGuest,
    )
}

/**
 * Linkplay's HTTP API (`/httpapi.asp?command=…`), used by Arylic, WiiM, Audio Pro and many
 * white-label Wi-Fi speakers. Command syntax follows Arylic's published API reference; the
 * channel-setting commands are community-documented only.
 */
class LinkplayClient(private val transport: HttpTransport) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Sends one command, plain HTTP first, then HTTPS (WiiM models). Returns the body and whether HTTPS worked. */
    suspend fun command(host: String, command: String, https: Boolean? = null): Pair<String, Boolean> {
        val schemes = when (https) { true -> listOf(true); false -> listOf(false); null -> listOf(false, true) }
        var last: Exception? = null
        for (secure in schemes) {
            try {
                val r = transport.execute(HttpRequest("GET", url(host, command, secure)))
                if (r.status in 200..299) return r.body to secure
                last = IOException("HTTP ${r.status} from $host")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No answer from $host")
    }

    /** Returns null when the host isn't a Linkplay device. */
    suspend fun probe(host: String): LinkplayDevice? {
        val (body, https) = runCatching { command(host, "getStatusEx") }.getOrNull() ?: return null
        val o = parse(body) ?: return null
        val uuid = o.str("uuid") ?: o.str("MAC") ?: return null
        val name = o.str("DeviceName") ?: o.str("ssid") ?: host
        val isGuest = o.str("group")?.let { it != "0" } ?: false
        return LinkplayDevice(uuid, name, host, https, o.str("firmware"), o.str("hardware") ?: o.str("project"), isGuest, o.str("master_uuid"))
    }

    suspend fun playerStatus(d: LinkplayDevice): JsonObject = parse(command(d.host, "getPlayerStatus", d.https).first) ?: JsonObject(emptyMap())

    suspend fun guests(d: LinkplayDevice): List<LinkplayGuest> {
        val o = parse(command(d.host, "multiroom:getSlaveList", d.https).first) ?: return emptyList()
        return (o["slave_list"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val ip = s.str("ip") ?: return@mapNotNull null
            LinkplayGuest(s.str("name") ?: ip, s.str("uuid"), ip, LinkplayChannel.of(s.str("channel")?.toIntOrNull()), s.str("volume")?.toIntOrNull(), s.str("mute")?.let { it == "1" })
        }
    }

    /** Sends each confirmed request in order; a body of "Failed"/"unknown command" counts as a failure. */
    suspend fun execute(confirmed: ConfirmedPlan) {
        for (req in confirmed.plan.requests) {
            val r = transport.execute(HttpRequest(req.method, req.url, req.headers, null))
            if (r.status !in 200..299) throw IOException("${req.label}: HTTP ${r.status}")
            val b = r.body.trim().lowercase()
            if (b.startsWith("failed") || b.contains("unknown command")) throw IOException("${req.label}: speaker answered \"${r.body.trim()}\"")
        }
    }

    fun playback(d: LinkplayDevice): PlaybackControl = LinkplayPlayback(this, d)

    internal fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body.trim()).jsonObject }.getOrNull()

    companion object {
        /** Linkplay URL-decodes the command, so only characters that would break the query need escaping. */
        fun url(host: String, command: String, https: Boolean): String {
            val encoded = command.replace("%", "%25").replace("+", "%2b").replace(" ", "%20").replace("&", "%26").replace("#", "%23")
            return "${if (https) "https" else "http"}://$host/httpapi.asp?command=$encoded"
        }

        /** Title/Artist/Album arrive hex-encoded. Non-hex values are returned as-is. */
        fun hexText(value: String?): String? {
            if (value.isNullOrBlank()) return null
            if (value.length % 2 != 0 || !value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return value
            return runCatching { String(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8) }.getOrDefault(value)
                .takeIf { it.isNotBlank() && it.lowercase() != "unknow" && it.lowercase() != "unknown" }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

class LinkplayPlayback(private val client: LinkplayClient, private val d: LinkplayDevice) : PlaybackControl, UrlPlayback {
    /** Arylic's documented `setPlayerCmd:play:<url>`; the title comes from the stream itself. */
    override suspend fun playUrl(url: String, title: String, live: Boolean) = cmd("setPlayerCmd:play:${url.trim()}")

    override val capabilities: Set<Capability> = setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT)

    override suspend fun status(): PlaybackStatus {
        val o = client.playerStatus(d)
        return PlaybackStatus(
            volume = o.str("vol")?.toIntOrNull(),
            maxVolume = 100,
            muted = o.str("mute")?.let { it == "1" },
            transport = when (o.str("status")) {
                "play" -> TransportState.PLAYING
                "pause" -> TransportState.PAUSED
                "stop" -> TransportState.STOPPED
                "load", "loading" -> TransportState.TRANSITIONING
                else -> TransportState.UNKNOWN
            },
            title = LinkplayClient.hexText(o.str("Title")),
            artist = LinkplayClient.hexText(o.str("Artist")),
        )
    }

    private suspend fun cmd(c: String) { client.command(d.host, c, d.https) }

    override suspend fun setVolume(volume: Int) = cmd("setPlayerCmd:vol:${volume.coerceIn(0, 100)}")
    override suspend fun setMuted(muted: Boolean) = cmd("setPlayerCmd:mute:${if (muted) 1 else 0}")
    override suspend fun play() = cmd("setPlayerCmd:resume")
    override suspend fun pause() = cmd("setPlayerCmd:pause")
    override suspend fun stop() = cmd("setPlayerCmd:stop")
    override suspend fun next() = cmd("setPlayerCmd:next")
    override suspend fun previous() = cmd("setPlayerCmd:prev")
}

/** Builds grouping/channel plans. Every one of these changes how speakers play together, so they need confirmation. */
object LinkplayPlanner {
    private fun get(d: LinkplayDevice, command: String, label: String) =
        PlannedRequest("GET", LinkplayClient.url(d.host, command, d.https), emptyMap(), "", label)

    fun join(host: LinkplayDevice, guest: LinkplayDevice): PlannedRequest =
        get(guest, "ConnectMasterAp:JoinGroupMaster:eth${host.host}:wifi0.0.0.0", "Join ${guest.name} → ${host.name}")

    fun kick(host: LinkplayDevice, guestIp: String, guestName: String): CommandPlan = CommandPlan(
        title = "Remove $guestName from ${host.name}'s group",
        summary = "$guestName goes back to playing on its own.",
        requests = listOf(get(host, "multiroom:SlaveKickout:$guestIp", "Kick $guestName")),
    )

    fun ungroup(host: LinkplayDevice): CommandPlan = CommandPlan(
        title = "Ungroup ${host.name}",
        summary = "Every guest leaves the group.",
        requests = listOf(get(host, "multiroom:Ungroup", "Ungroup ${host.name}")),
    )

    fun group(host: LinkplayDevice, guests: List<LinkplayDevice>): CommandPlan = CommandPlan(
        title = "Group ${guests.joinToString(", ") { it.name }} with ${host.name}",
        summary = "${host.name} hosts; the others play in sync with it.",
        requests = guests.map { join(host, it) },
    )

    fun guestChannel(host: LinkplayDevice, guestIp: String, guestName: String, ch: LinkplayChannel): PlannedRequest =
        get(host, "multiroom:SlaveChannel:$guestIp:${ch.code}", "$guestName → ${ch.label.lowercase()}")

    fun ownChannel(d: LinkplayDevice, ch: LinkplayChannel): PlannedRequest =
        get(d, "setPlayerCmd:slave_channel:${ch.code}", "${d.name} → ${ch.label.lowercase()}")

    const val CHANNEL_WARNING =
        "Setting left/right channels isn't in Linkplay's published API; these commands come from user reports and may not work on every model or firmware."

    /** Works across models and brands that share the Linkplay platform: group them, then split the channels. */
    fun stereoPair(left: LinkplayDevice, right: LinkplayDevice): CommandPlan = CommandPlan(
        title = "Stereo pair ${left.name} + ${right.name}",
        summary = "${right.name} joins ${left.name}'s group. ${left.name} plays the left channel, ${right.name} the right.",
        requests = listOf(join(left, right), ownChannel(left, LinkplayChannel.LEFT), guestChannel(left, right.host, right.name, LinkplayChannel.RIGHT)),
        warnings = listOf(CHANNEL_WARNING),
    )

    fun setChannel(host: LinkplayDevice, target: LinkplayDevice?, guest: LinkplayGuest?, ch: LinkplayChannel): CommandPlan {
        val req = if (guest != null) guestChannel(host, guest.ip, guest.name, ch) else ownChannel(target ?: host, ch)
        val who = guest?.name ?: (target ?: host).name
        return CommandPlan("Set $who to ${ch.label.lowercase()}", "$who will play the ${ch.label.lowercase()} channel.", listOf(req), listOf(CHANNEL_WARNING))
    }
}

/** Probes candidate hosts (from SSDP) and assembles devices with their group guests. */
class LinkplayHousehold(private val client: LinkplayClient) {
    suspend fun load(hosts: Collection<String>): List<LinkplayDevice> = coroutineScope {
        val found = hosts.distinct().map { h -> async { withTimeoutOrNull(4_000) { client.probe(h) } } }.awaitAll().filterNotNull()
        found.map { d ->
            async {
                if (d.isGuest) {
                    d
                } else {
                    val guests = runCatching { client.guests(d) }.getOrDefault(emptyList())
                    val ch = runCatching { LinkplayChannel.of(client.playerStatus(d).str("ch")?.toIntOrNull()) }.getOrDefault(LinkplayChannel.STEREO)
                    d.copy(guests = guests, channel = ch)
                }
            }
        }.awaitAll().distinctBy { it.uuid }.sortedBy { it.name.lowercase() }
    }
}

/**
 * OkHttp client that accepts self-signed certificates (WiiM serves its API over HTTPS with one).
 * Only use it behind LanOnlyTransport, which refuses anything but private-network hosts.
 */
object LinkplayHttp {
    fun lanClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
