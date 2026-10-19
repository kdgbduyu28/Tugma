package com.timborja.tugma.cast.demo

import com.timborja.tugma.cast.CastConnection
import com.timborja.tugma.cast.CastConnector
import com.timborja.tugma.cast.CastDevice
import com.timborja.tugma.cast.CastDiscoverer
import com.timborja.tugma.cast.CastMessage
import com.timborja.tugma.cast.CastNamespaces
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/** Cast devices that answer CASTV2 messages from in-memory state. Brand-neutral names on purpose. */
class FakeCastHousehold(
    devices: List<Device> = DEFAULT_DEVICES,
    private val latencyMillis: Long = 0,
) : CastConnector, CastDiscoverer {

    class App(
        val appId: String,
        val displayName: String,
        val transportId: String,
        val mediaNamespace: Boolean,
        val idleScreen: Boolean = false,
        var playerState: String? = "PLAYING",
        var title: String? = null,
        var artist: String? = null,
        val mediaSessionId: Int = 1,
    )

    class Device(
        val info: CastDevice,
        var level: Double = 0.3,
        var muted: Boolean = false,
        val fixedVolume: Boolean = false,
        var app: App? = null,
    )

    val devices: List<Device> = devices.toList()

    /** Every message type the fakes received, e.g. "SET_VOLUME", for test assertions. */
    val received = mutableListOf<String>()

    override suspend fun discover(): List<CastDevice> {
        if (latencyMillis > 0) delay(latencyMillis * 3)
        return devices.map { it.info }
    }

    override suspend fun connect(host: String, port: Int): CastConnection {
        if (latencyMillis > 0) delay(latencyMillis)
        val d = devices.firstOrNull { it.info.host == host && it.info.port == port }
            ?: throw java.net.ConnectException("No Cast device at $host:$port")
        return Conn(d)
    }

    private inner class Conn(private val d: Device) : CastConnection {
        private val inbox = Channel<CastMessage>(Channel.UNLIMITED)
        private var closed = false

        init {
            // Real receivers ping periodically; send one up front so clients must answer it.
            inbox.trySend(CastMessage(CastNamespaces.RECEIVER_ID, CastNamespaces.SENDER, CastNamespaces.HEARTBEAT, """{"type":"PING"}"""))
        }

        override suspend fun send(message: CastMessage) {
            if (closed) throw IOException("closed")
            val json = message.json ?: return
            val type = message.type ?: return
            synchronized(this@FakeCastHousehold) { received += type }
            val id = message.requestId
            val reply: JsonObject? = synchronized(this@FakeCastHousehold) {
                when (message.namespace) {
                    CastNamespaces.RECEIVER -> receiver(type, json, id)
                    CastNamespaces.MEDIA -> media(message.destinationId, type, json, id)
                    else -> null
                }
            }
            if (reply != null) {
                inbox.trySend(CastMessage(message.destinationId, message.sourceId, message.namespace, reply.toString()))
            }
        }

        override suspend fun receive(): CastMessage = inbox.receive()

        override fun close() {
            closed = true
            inbox.close()
        }

        private fun receiver(type: String, json: JsonObject, id: Int?): JsonObject? = when (type) {
            "GET_STATUS" -> receiverStatus(id)
            "SET_VOLUME" -> {
                val v = json["volume"]?.jsonObject
                v?.get("level")?.jsonPrimitive?.doubleOrNull?.let { d.level = it.coerceIn(0.0, 1.0) }
                v?.get("muted")?.jsonPrimitive?.booleanOrNull?.let { d.muted = it }
                receiverStatus(id)
            }
            else -> error(id, "INVALID_REQUEST")
        }

        private fun receiverStatus(id: Int?) = buildJsonObject {
            put("type", "RECEIVER_STATUS")
            id?.let { put("requestId", it) }
            putJsonObject("status") {
                putJsonObject("volume") {
                    put("level", d.level)
                    put("muted", d.muted)
                    put("controlType", if (d.fixedVolume) "fixed" else "attenuation")
                    put("stepInterval", 0.05)
                }
                putJsonArray("applications") {
                    d.app?.let { a ->
                        addJsonObject {
                            put("appId", a.appId)
                            put("displayName", a.displayName)
                            put("isIdleScreen", a.idleScreen)
                            put("sessionId", "session-${a.appId}")
                            put("statusText", if (a.idleScreen) "Ready to cast" else "Casting: ${a.displayName}")
                            put("transportId", a.transportId)
                            putJsonArray("namespaces") {
                                if (a.mediaNamespace) addJsonObject { put("name", CastNamespaces.MEDIA) }
                                addJsonObject { put("name", "urn:x-cast:com.example.custom") }
                            }
                        }
                    }
                }
            }
        }

        private fun media(destination: String, type: String, json: JsonObject, id: Int?): JsonObject? {
            val a = d.app?.takeIf { it.transportId == destination && it.mediaNamespace } ?: return null
            if (type != "GET_STATUS") {
                val session = json["mediaSessionId"]?.jsonPrimitive?.intOrNull
                if (session != a.mediaSessionId || a.playerState == null) return error(id, "INVALID_REQUEST")
                when (type) {
                    "PLAY" -> a.playerState = "PLAYING"
                    "PAUSE" -> a.playerState = "PAUSED"
                    "STOP" -> a.playerState = null
                    "QUEUE_UPDATE" -> a.title = (a.title ?: "Track") + " (skipped ${json["jump"]?.jsonPrimitive?.contentOrNull})"
                    else -> return error(id, "INVALID_REQUEST")
                }
            }
            return buildJsonObject {
                put("type", "MEDIA_STATUS")
                id?.let { put("requestId", it) }
                put(
                    "status",
                    buildJsonArray {
                        if (a.playerState != null) {
                            addJsonObject {
                                put("mediaSessionId", a.mediaSessionId)
                                put("playerState", a.playerState)
                                putJsonObject("media") {
                                    putJsonObject("metadata") {
                                        a.title?.let { put("title", it) }
                                        a.artist?.let { put("artist", it) }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }

        private fun error(id: Int?, type: String) = buildJsonObject {
            put("type", type)
            id?.let { put("requestId", it) }
            put("reason", "INVALID_COMMAND")
        }
    }

    companion object {
        private fun device(id: String, name: String, model: String, host: String, port: Int = CastDevice.DEFAULT_PORT) =
            CastDevice(id, name, model, host, port)

        val DEFAULT_DEVICES: List<Device>
            get() = listOf(
                Device(
                    device("demo-cast-kwarto", "Kwarto Speaker", "Demo Cast Speaker", "192.168.50.41"),
                    level = 0.25,
                    app = App("CC1AD845", "Default Media Receiver", "web-1", mediaNamespace = true, title = "Demo Radio", artist = "Tugma FM"),
                ),
                Device(
                    device("demo-cast-opisina", "Opisina Display", "Demo Cast Display", "192.168.50.42"),
                    app = App("E8C28D3C", "Backdrop", "web-2", mediaNamespace = false, idleScreen = true, playerState = null),
                ),
                Device(
                    device("demo-cast-sala", "Sala TV", "Demo Cast TV", "192.168.50.43"),
                    level = 0.5,
                    app = App("DEMO0001", "Demo Music App", "web-3", mediaNamespace = false),
                ),
                Device(
                    device("demo-cast-bahay", "Buong Bahay", CastDevice.GROUP_MODEL, "192.168.50.41", port = 32187),
                    level = 0.4,
                    app = App("CC1AD845", "Default Media Receiver", "web-4", mediaNamespace = true, playerState = "PAUSED", title = "Group playlist"),
                ),
            )
    }
}
