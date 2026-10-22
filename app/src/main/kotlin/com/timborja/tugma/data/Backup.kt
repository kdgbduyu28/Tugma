package com.timborja.tugma.data

import com.timborja.tugma.core.Station
import com.timborja.tugma.eq.EqLibrary
import com.timborja.tugma.sonos.SonosScene
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Everything worth keeping across a reinstall: EQ profiles, saved links, scenes and speakers
 * added by IP. A null section means "not in this backup", so restoring leaves it alone.
 */
data class Backup(
    val eq: EqLibrary? = null,
    val stations: List<Station>? = null,
    val scenes: List<SonosScene>? = null,
    val manualHosts: Set<String>? = null,
    /** Speakers hidden from the list, as "BRAND|id". */
    val hiddenSpeakers: Set<String>? = null,
    /** Local nicknames, "BRAND|id" → name. */
    val nicknames: Map<String, String>? = null,
) {
    val summary: String
        get() = listOfNotNull(
            eq?.let { "${it.profiles.size} EQ profiles" },
            stations?.let { "${it.size} links" },
            scenes?.let { "${it.size} scenes" },
            manualHosts?.let { "${it.size} speakers added by IP" },
            hiddenSpeakers?.takeIf { it.isNotEmpty() }?.let { "${it.size} hidden speakers" },
            nicknames?.takeIf { it.isNotEmpty() }?.let { "${it.size} nicknames" },
        ).joinToString(", ").ifEmpty { "nothing" }
}

class BackupFormatException(message: String) : IllegalArgumentException(message)

object BackupCodec {
    const val FORMAT = "tugma-backup"
    const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stations = ListSerializer(Station.serializer())
    private val scenes = ListSerializer(SonosScene.serializer())

    fun encode(b: Backup, createdAtMillis: Long): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAtMillis", createdAtMillis)
            b.eq?.let { put("eq", json.encodeToJsonElement(EqLibrary.serializer(), it)) }
            b.stations?.let { put("stations", json.encodeToJsonElement(stations, it)) }
            b.scenes?.let { put("scenes", json.encodeToJsonElement(scenes, it)) }
            b.manualHosts?.let { put("manualHosts", JsonArray(it.sorted().map(::JsonPrimitive))) }
            b.hiddenSpeakers?.let { put("hiddenSpeakers", JsonArray(it.sorted().map(::JsonPrimitive))) }
            b.nicknames?.let { m -> put("nicknames", JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) })) }
        },
    )

    fun decode(text: String): Backup {
        if (text.length > MAX_BYTES) throw BackupFormatException("That file is too large to be a Tugma backup")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw BackupFormatException("That file isn't a Tugma backup (not JSON)")
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) throw BackupFormatException("That file isn't a Tugma backup")
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
        if (version > VERSION) throw BackupFormatException("This backup is from a newer Tugma (format $version); update the app first")
        fun <T> section(key: String, read: (kotlinx.serialization.json.JsonElement) -> T): T? =
            root[key]?.let { el -> runCatching { read(el) }.getOrElse { throw BackupFormatException("The $key section is damaged") } }
        return Backup(
            eq = section("eq") { json.decodeFromJsonElement(EqLibrary.serializer(), it) },
            stations = section("stations") { el -> json.decodeFromJsonElement(stations, el).filter { it.isValidUrl } },
            scenes = section("scenes") { json.decodeFromJsonElement(scenes, it) },
            manualHosts = section("manualHosts") { el ->
                (el as JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { h -> h.isNotEmpty() && h.length <= 100 } }.toSet()
            },
            nicknames = section("nicknames") { el ->
                el.jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.trim()?.take(40)?.takeIf { k.contains('|') && it.isNotEmpty() }?.let { k to it } }.toMap()
            },
            hiddenSpeakers = section("hiddenSpeakers") { el ->
                (el as JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { k -> k.contains('|') && k.length <= 200 } }.toSet()
            },
        )
    }
}
