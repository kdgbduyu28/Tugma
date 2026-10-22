package com.timborja.tugma.linkplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

enum class LinkplayInput(val mode: String, val label: String) {
    WIFI("wifi", "Wi-Fi"),
    LINE_IN("line-in", "Line-in"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    OPTICAL("optical", "Optical"),
    USB("udisk", "USB drive"),
}

data class LinkplayPreset(val number: Int, val name: String, val source: String?)

/**
 * Presets and EQ. `MCUKeyShortClick:<n>` is in Arylic's documented API and works across Linkplay
 * firmware. `getPresetInfo` and the `EQ*` commands come from WiiM's documented API and may be
 * missing elsewhere: those calls return null instead of failing, and the UI hides what's missing.
 */
class LinkplayExtras(private val client: LinkplayClient, private val d: LinkplayDevice) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private suspend fun cmd(c: String): String = client.command(d.host, c, d.https).first.trim()

    /** Named presets, or null when the firmware doesn't list them (presets 1–10 may still play). */
    suspend fun presets(): List<LinkplayPreset>? {
        val obj = parseObject(cmd("getPresetInfo")) ?: return null
        val list = obj["preset_list"] as? JsonArray ?: return null
        return list.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val n = (p["number"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return@mapNotNull null
            LinkplayPreset(n, p.text("name")?.takeIf { it.isNotBlank() } ?: "Preset $n", p.text("source")?.takeIf { it.isNotBlank() })
        }.sortedBy { it.number }
    }

    suspend fun playPreset(number: Int) {
        require(number in 1..MAX_PRESETS) { "Preset must be 1–$MAX_PRESETS" }
        check(cmd("MCUKeyShortClick:$number").ok()) { "The speaker refused preset $number" }
    }

    /** True/false when the speaker reports EQ state; null when it has no EQ commands. */
    suspend fun eqEnabled(): Boolean? = parseObject(cmd("EQGetStat"))?.text("EQStat")?.equals("On", ignoreCase = true)

    /** EQ preset names, or empty when unsupported. */
    suspend fun eqPresets(): List<String> {
        val body = cmd("EQGetList")
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }

    suspend fun setEq(enabled: Boolean) {
        check(cmd(if (enabled) "EQOn" else "EQOff").ok()) { "The speaker refused EQ ${if (enabled) "on" else "off"}" }
    }

    suspend fun loadEq(name: String) {
        check(cmd("EQLoad:$name").ok()) { "The speaker refused EQ preset $name" }
    }

    /** Switches the input with Arylic's `setPlayerCmd:switchmode:<mode>`. Which inputs exist depends on the model. */
    suspend fun switchInput(input: LinkplayInput) {
        check(cmd("setPlayerCmd:switchmode:${input.mode}").ok()) { "The speaker refused ${input.label}" }
    }

    /** Arylic's documented `setDeviceName:<name>`. */
    suspend fun rename(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 32) { "Names are 1–32 characters" }
        check(cmd("setDeviceName:$clean").ok()) { "The speaker refused the new name" }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parseObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** "OK", or a JSON object whose status isn't a failure. */
    private fun String.ok(): Boolean {
        if (equals("OK", ignoreCase = true)) return true
        val status = parseObject(this)?.text("status") ?: return false
        return !status.equals("Failed", ignoreCase = true)
    }

    companion object {
        const val MAX_PRESETS = 10
    }
}
