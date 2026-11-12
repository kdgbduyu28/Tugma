package com.timborja.tugma.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Speaker keys as the mixer, hidden list and nicknames store them: "BRAND|id". */
object SpeakerKey {
    fun of(brand: Brand, id: String): String = "${brand.name}|$id"

    fun parse(key: String): Pair<Brand, String>? {
        val brand = Brand.entries.firstOrNull { it.name == key.substringBefore('|', "") } ?: return null
        return key.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { brand to it }
    }
}

/** Sets several speakers' volumes at once, e.g. the non-Sonos part of a scene. */
object VolumePreset {
    /** Returns how many speakers took their volume; ones that are gone or don't answer are skipped. */
    suspend fun apply(volumes: Map<String, Int>, lookup: (String) -> PlaybackControl?): Int = coroutineScope {
        volumes.map { (key, v) -> async { lookup(key)?.let { c -> runCatching { c.setVolume(v.coerceIn(0, 100)) }.isSuccess } ?: false } }
            .awaitAll().count { it }
    }
}
