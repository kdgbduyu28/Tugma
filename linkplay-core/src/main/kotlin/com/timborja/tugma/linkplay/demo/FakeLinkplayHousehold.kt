package com.timborja.tugma.linkplay.demo

import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.HttpRequest
import com.timborja.tugma.upnp.HttpResponse
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.SsdpResponse
import com.timborja.tugma.upnp.dlna.UpnpServices
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

/** Linkplay speakers answering `/httpapi.asp` from memory. Brand-neutral names on purpose. */
class FakeLinkplayHousehold(private val latencyMillis: Long = 0) : HttpTransport, Discoverer {
    class Speaker(val uuid: String, var name: String, val host: String, val https: Boolean = false, val hardware: String = "A31") {
        var volume = 30
        var muted = false
        var status = "stop"
        var channel = 0
        var hostIp: String? = null
        var title = "Demo Track"
        var eqOn = false
        var eqPreset = "Flat"
    }

    /** Preset names the fake reports. Only the WiiM-style (HTTPS) speaker lists them; plain Linkplay firmware may not. */
    val presetNames = listOf("Morning Radio", "Focus Playlist", "Rainy Day Jazz")
    private val eqNames = listOf("Flat", "Acoustic", "Bass Booster", "Vocal Booster")

    val speakers = listOf(
        Speaker("FF31F09E-1A5B-4E36-8A0D-000000000051", "Kusina Amp", "192.168.50.51"),
        Speaker("FF31F09E-1A5B-4E36-8A0D-000000000052", "Sala Left", "192.168.50.52", hardware = "A98"),
        Speaker("FF31F09E-1A5B-4E36-8A0D-000000000053", "Sala Right", "192.168.50.53", https = true, hardware = "WiiM Mini"),
    )
    val commands = mutableListOf<String>()

    override suspend fun search(): List<SsdpResponse> = speakers.map {
        SsdpResponse("http://${it.host}:49152/description.xml", UpnpServices.MEDIA_RENDERER_ST, "uuid:${it.uuid}", "Linux UPnP/1.0", emptyMap())
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (latencyMillis > 0) delay(latencyMillis)
        val uri = URI(request.url)
        val s = speakers.firstOrNull { it.host == uri.host } ?: throw java.net.ConnectException("No route to ${uri.host}")
        if ((uri.scheme == "https") != s.https) throw java.net.ConnectException("${uri.scheme} not served by ${s.host}")
        if (uri.path != "/httpapi.asp") return HttpResponse(404, "")
        val cmd = URLDecoder.decode(uri.rawQuery.removePrefix("command="), "UTF-8")
        synchronized(this) { commands += "${s.name}: $cmd" }
        return synchronized(this) { HttpResponse(200, handle(s, cmd)) }
    }

    private fun guestsOf(h: Speaker) = speakers.filter { it.hostIp == h.host }

    private fun handle(s: Speaker, cmd: String): String {
        val parts = cmd.split(":")
        return when {
            cmd == "getStatusEx" -> """{"uuid":"${s.uuid}","DeviceName":"${s.name}","firmware":"4.6.415145","hardware":"${s.hardware}","project":"${s.hardware}",""" +
                """"group":"${if (s.hostIp != null) 1 else 0}","master_uuid":"${s.hostIp?.let { ip -> speakers.first { it.host == ip }.uuid }.orEmpty()}","MAC":"00:22:6C:00:00:${s.host.takeLast(2)}"}"""
            cmd == "getPlayerStatus" -> """{"type":"${if (s.hostIp != null) 1 else 0}","ch":"${s.channel}","mode":"10","loop":"0","status":"${s.status}",""" +
                """"curpos":"0","totlen":"0","Title":"${hex(s.title)}","Artist":"${hex("Tugma FM")}","Album":"","vol":"${s.volume}","mute":"${if (s.muted) 1 else 0}"}"""
            cmd == "multiroom:getSlaveList" -> {
                val g = guestsOf(s)
                """{"slaves":${g.size},"wmrm_version":"4.2","slave_list":[""" + g.joinToString(",") {
                    """{"name":"${it.name}","uuid":"${it.uuid}","ip":"${it.host}","version":"4.2","type":"WiiMu-A31","channel":${it.channel},"volume":${it.volume},"mute":${if (it.muted) 1 else 0}}"""
                } + "]}"
            }
            cmd.startsWith("setPlayerCmd:vol:") -> { s.volume = parts[2].toInt().coerceIn(0, 100); "OK" }
            cmd.startsWith("setPlayerCmd:mute:") -> { s.muted = parts[2] == "1"; "OK" }
            cmd == "setPlayerCmd:resume" || cmd == "setPlayerCmd:onepause" -> { s.status = "play"; "OK" }
            cmd == "setPlayerCmd:pause" -> { s.status = "pause"; "OK" }
            cmd == "setPlayerCmd:stop" -> { s.status = "stop"; "OK" }
            cmd == "setPlayerCmd:next" || cmd == "setPlayerCmd:prev" -> "OK"
            cmd.startsWith("setPlayerCmd:slave_channel:") -> { s.channel = parts[2].toInt(); "OK" }
            cmd.startsWith("ConnectMasterAp:JoinGroupMaster:eth") -> {
                val hostIp = cmd.removePrefix("ConnectMasterAp:JoinGroupMaster:eth").substringBefore(":wifi")
                val h = speakers.firstOrNull { it.host == hostIp } ?: return "Failed"
                if (h === s || h.hostIp != null) return "Failed"
                s.hostIp = h.host
                "OK"
            }
            cmd.startsWith("multiroom:SlaveKickout:") -> guestsOf(s).firstOrNull { it.host == parts[2] }?.let { it.hostIp = null; it.channel = 0; "OK" } ?: "Failed"
            cmd == "multiroom:Ungroup" -> { guestsOf(s).forEach { it.hostIp = null; it.channel = 0 }; s.channel = 0; "OK" }
            cmd.startsWith("multiroom:SlaveChannel:") -> guestsOf(s).firstOrNull { it.host == parts[2] }?.let { it.channel = parts[3].toInt(); "OK" } ?: "Failed"
            cmd.startsWith("setPlayerCmd:switchmode:") -> when (val m = cmd.removePrefix("setPlayerCmd:switchmode:")) {
                "wifi", "line-in", "bluetooth" -> { s.title = if (m == "wifi") "Demo Track" else m; "OK" }
                else -> "Failed" // this demo hardware has no optical or USB input
            }
            cmd.startsWith("setDeviceName:") -> cmd.removePrefix("setDeviceName:").takeIf { it.isNotBlank() }?.let { s.name = it; "OK" } ?: "Failed"
            cmd.startsWith("setPlayerCmd:play:") -> { s.title = cmd.removePrefix("setPlayerCmd:play:").substringAfterLast('/'); s.status = "play"; "OK" }
            cmd.startsWith("MCUKeyShortClick:") -> parts[1].toIntOrNull()?.takeIf { it in 1..10 }?.let { n ->
                s.title = presetNames.getOrNull(n - 1) ?: "Preset $n"
                s.status = "play"
                "OK"
            } ?: "Failed"
            s.https && cmd == "getPresetInfo" -> """{"preset_num":${presetNames.size},"preset_list":[""" +
                presetNames.mapIndexed { i, n -> """{"number":${i + 1},"name":"$n","url":"","source":"Demo","picurl":""}""" }.joinToString(",") + "]}"
            s.https && cmd == "EQGetStat" -> """{"EQStat":"${if (s.eqOn) "On" else "Off"}"}"""
            s.https && cmd == "EQGetList" -> eqNames.joinToString(",", "[", "]") { "\"$it\"" }
            s.https && cmd == "EQOn" -> { s.eqOn = true; "OK" }
            s.https && cmd == "EQOff" -> { s.eqOn = false; "OK" }
            s.https && cmd.startsWith("EQLoad:") -> cmd.removePrefix("EQLoad:").takeIf { it in eqNames }
                ?.let { s.eqPreset = it; s.eqOn = true; """{"status":"OK","Name":"$it"}""" } ?: """{"status":"Failed"}"""
            else -> "unknown command"
        }
    }

    private fun hex(t: String) = t.toByteArray().joinToString("") { "%02X".format(it) }
}
