package com.timborja.tugma.cast

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.UrlPlayback
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.SpeakerSummary
import com.timborja.tugma.core.TransportState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** A Cast device or group found over mDNS (`_googlecast._tcp`). */
data class CastDevice(
    val id: String,
    val name: String,
    val model: String,
    val host: String,
    val port: Int,
    /** mDNS `rs` record, e.g. "Spotify" when something is casting. */
    val statusText: String? = null,
) {
    /** Groups (and pairs) made in Google Home; Tugma can control but not edit them. */
    val isGroup: Boolean get() = model.equals(GROUP_MODEL, ignoreCase = true)

    fun toSummary(): SpeakerSummary = SpeakerSummary(
        id = id,
        brand = Brand.CAST,
        name = name,
        model = model,
        host = host,
        port = port,
        capabilities = setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT),
        roleLabel = if (isGroup) "Speaker group from Google Home (read-only)" else statusText?.let { "Casting: $it" },
        manufacturer = model.takeIf { it.startsWith("Google", ignoreCase = true) }?.let { "Google" },
    )

    companion object {
        const val GROUP_MODEL = "Google Cast Group"
        const val BY_IP_MODEL = "Google Cast (added by IP)"
        const val DEFAULT_PORT = 8009

        /** Builds a device from an mDNS record's TXT attributes (`id`, `fn`, `md`, `rs`). */
        fun fromTxt(attributes: Map<String, ByteArray?>, serviceName: String, host: String, port: Int): CastDevice {
            fun txt(key: String) = attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            return CastDevice(
                id = txt("id") ?: serviceName,
                name = txt("fn") ?: serviceName.substringBeforeLast('-'),
                model = txt("md") ?: "Cast device",
                host = host,
                port = port,
                statusText = txt("rs"),
            )
        }
    }
}

/** Platform discovery (Android's NsdManager in the app, a fake in tests/demo). */
/**
 * Checks that [host] speaks CASTV2, for devices added by IP when mDNS is blocked. The protocol
 * doesn't carry a friendly name, so the device is labelled by its address.
 */
suspend fun CastClient.probe(host: String, port: Int = CastDevice.DEFAULT_PORT): CastDevice? = runCatching {
    session(host, port) { request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, kotlinx.serialization.json.buildJsonObject { put("type", "GET_STATUS") }) }
    CastDevice(id = "ip:$host:$port", name = "Cast device at $host", model = CastDevice.BY_IP_MODEL, host = host, port = port)
}.getOrNull()

fun interface CastDiscoverer {
    suspend fun discover(): List<CastDevice>
}

data class CastApp(
    val appId: String,
    val displayName: String?,
    val statusText: String?,
    val transportId: String?,
    val namespaces: List<String>,
    val sessionId: String? = null,
    val isIdleScreen: Boolean,
) {
    val supportsMedia: Boolean get() = CastNamespaces.MEDIA in namespaces && !isIdleScreen && transportId != null
}

data class ReceiverStatus(val volumeLevel: Double?, val muted: Boolean?, val fixedVolume: Boolean, val apps: List<CastApp>) {
    val mediaApp: CastApp? get() = apps.firstOrNull { it.supportsMedia }
    val activeApp: CastApp? get() = apps.firstOrNull { !it.isIdleScreen }
}

data class MediaStatus(val mediaSessionId: Int?, val playerState: String?, val title: String?, val artist: String?)

/** Parses the JSON replies from the receiver and media namespaces. */
object CastJson {
    fun receiverStatus(json: JsonObject): ReceiverStatus {
        val status = json["status"]?.jsonObject ?: JsonObject(emptyMap())
        val volume = status["volume"]?.jsonObject
        val apps = (status["applications"] as? JsonArray).orEmpty().map { a ->
            val o = a.jsonObject
            CastApp(
                appId = o.string("appId").orEmpty(),
                displayName = o.string("displayName"),
                statusText = o.string("statusText"),
                transportId = o.string("transportId"),
                namespaces = (o["namespaces"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") },
                isIdleScreen = o["isIdleScreen"]?.jsonPrimitive?.booleanOrNull ?: false,
                sessionId = o.string("sessionId"),
            )
        }
        return ReceiverStatus(
            volumeLevel = volume?.get("level")?.jsonPrimitive?.doubleOrNull,
            muted = volume?.get("muted")?.jsonPrimitive?.booleanOrNull,
            fixedVolume = volume?.string("controlType") == "fixed",
            apps = apps,
        )
    }

    /** First media session in a MEDIA_STATUS reply, or null if nothing is loaded. */
    fun mediaStatus(json: JsonObject): MediaStatus? {
        val s = (json["status"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val metadata = s["media"]?.jsonObject?.get("metadata")?.jsonObject
        return MediaStatus(
            mediaSessionId = s["mediaSessionId"]?.jsonPrimitive?.intOrNull,
            playerState = s.string("playerState"),
            title = metadata?.string("title"),
            artist = metadata?.string("artist") ?: metadata?.string("albumArtist") ?: metadata?.string("subtitle"),
        )
    }

    fun transport(playerState: String?): TransportState = when (playerState?.uppercase()) {
        "PLAYING" -> TransportState.PLAYING
        "PAUSED" -> TransportState.PAUSED
        "BUFFERING", "LOADING" -> TransportState.TRANSITIONING
        "IDLE" -> TransportState.STOPPED
        else -> TransportState.UNKNOWN
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}

/**
 * Volume/mute through the receiver namespace; play/pause/next through the media namespace of
 * whatever app is casting. Apps that don't expose the media namespace (some music apps use their
 * own) can still have their volume changed but not their playback.
 */
class CastPlayback(private val client: CastClient, private val host: String, private val port: Int) : PlaybackControl, UrlPlayback {
    /**
     * Launches Google's Default Media Receiver if it isn't running, then LOADs the link with a
     * title. Same flow as pychromecast `play_media`.
     */
    override suspend fun playUrl(url: String, title: String, live: Boolean) {
        client.session(host, port) {
            var receiver = CastJson.receiverStatus(request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, type("GET_STATUS")))
            var app = receiver.apps.firstOrNull { it.appId == DEFAULT_MEDIA_RECEIVER && it.transportId != null }
            if (app == null) {
                receiver = CastJson.receiverStatus(
                    request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, buildJsonObject { put("type", "LAUNCH"); put("appId", DEFAULT_MEDIA_RECEIVER) }),
                )
                app = receiver.apps.firstOrNull { it.appId == DEFAULT_MEDIA_RECEIVER && it.transportId != null }
                    ?: throw CastException("The device didn't start the media receiver")
            }
            request(
                CastNamespaces.MEDIA, app.transportId!!,
                buildJsonObject {
                    put("type", "LOAD")
                    put("autoplay", true)
                    put(
                        "media",
                        buildJsonObject {
                            put("contentId", url.trim())
                            put("contentUrl", url.trim())
                            put("contentType", contentType(url))
                            put("streamType", if (live) "LIVE" else "BUFFERED")
                            put("metadata", buildJsonObject { put("metadataType", 0); put("title", title) })
                        },
                    )
                },
            )
        }
    }

    /** Closes whatever app is casting (receiver STOP with its session), back to the idle screen. */
    suspend fun stopCasting() {
        client.session(host, port) {
            val receiver = CastJson.receiverStatus(request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, type("GET_STATUS")))
            val app = receiver.activeApp ?: throw CastException("Nothing is casting to this device")
            val sessionId = app.sessionId ?: throw CastException("The casting app didn't report a session")
            request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, buildJsonObject { put("type", "STOP"); put("sessionId", sessionId) })
        }
    }

    private fun contentType(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "aac" -> "audio/aac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m3u8" -> "application/x-mpegURL"
        else -> "audio/mpeg"
    }

    override val capabilities: Set<Capability> = setOf(Capability.VOLUME, Capability.MUTE, Capability.TRANSPORT)

    override suspend fun status(): PlaybackStatus = client.session(host, port) {
        val receiver = CastJson.receiverStatus(request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, type("GET_STATUS")))
        val app = receiver.mediaApp
        val media = app?.let { a ->
            runCatching { CastJson.mediaStatus(request(CastNamespaces.MEDIA, a.transportId!!, type("GET_STATUS"))) }.getOrNull()
        }
        PlaybackStatus(
            volume = receiver.volumeLevel?.let { (it * 100).roundToInt() },
            maxVolume = 100,
            muted = receiver.muted,
            transport = when {
                media != null -> CastJson.transport(media.playerState)
                receiver.activeApp == null -> TransportState.NO_MEDIA
                else -> TransportState.UNKNOWN
            },
            title = media?.title ?: receiver.activeApp?.let { it.statusText ?: it.displayName },
            artist = media?.artist,
        )
    }

    override suspend fun setVolume(volume: Int) {
        client.session(host, port) {
            val receiver = CastJson.receiverStatus(request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, type("GET_STATUS")))
            if (receiver.fixedVolume) throw CastException("This device has fixed volume")
            request(
                CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID,
                buildJsonObject {
                    put("type", "SET_VOLUME")
                    put("volume", buildJsonObject { put("level", volume.coerceIn(0, 100) / 100.0) })
                },
            )
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        client.session(host, port) {
            request(
                CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID,
                buildJsonObject {
                    put("type", "SET_VOLUME")
                    put("volume", buildJsonObject { put("muted", muted) })
                },
            )
        }
    }

    override suspend fun play() = media("PLAY")
    override suspend fun pause() = media("PAUSE")
    override suspend fun stop() = media("STOP")
    override suspend fun next() = media("QUEUE_UPDATE", jump = 1)
    override suspend fun previous() = media("QUEUE_UPDATE", jump = -1)

    private suspend fun media(command: String, jump: Int? = null) {
        client.session(host, port) {
            val receiver = CastJson.receiverStatus(request(CastNamespaces.RECEIVER, CastNamespaces.RECEIVER_ID, type("GET_STATUS")))
            val app = receiver.mediaApp ?: throw CastException(
                if (receiver.activeApp == null) "Nothing is casting to this device"
                else "${receiver.activeApp?.displayName ?: "This app"} doesn't accept playback control over the network",
            )
            val transport = app.transportId!!
            val session = CastJson.mediaStatus(request(CastNamespaces.MEDIA, transport, type("GET_STATUS")))?.mediaSessionId
                ?: throw CastException("Nothing is loaded in ${app.displayName ?: "the casting app"}")
            request(
                CastNamespaces.MEDIA, transport,
                buildJsonObject {
                    put("type", command)
                    put("mediaSessionId", session)
                    if (jump != null) put("jump", jump)
                },
            )
        }
    }

    private fun type(t: String) = buildJsonObject { put("type", JsonPrimitive(t)) }

    companion object {
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
