package com.timborja.tugma.data

import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.SpeakerKey
import com.timborja.tugma.core.VolumePreset
import com.timborja.tugma.sonos.SonosScene

/**
 * The non-Sonos volumes a scene sets. They're everyday volume changes, so they're sent after the
 * reviewed Sonos plan rather than inside it. Returns how many speakers took their volume.
 */
suspend fun AppContainer.applyOtherVolumes(scene: SonosScene): Int {
    if (scene.otherVolumes.isEmpty()) return 0
    val wanted = scene.otherVolumes.keys.mapNotNull(SpeakerKey::parse).map { it.first }.toSet()
    // A cold start has empty lists; scan only the brands the scene needs.
    wanted.forEach { b -> source(b)?.let { s -> if (s.summaries.value.speakers.isEmpty()) runCatching { s.refresh() } } }
    return VolumePreset.apply(scene.otherVolumes) { key -> SpeakerKey.parse(key)?.let { (b, id) -> source(b)?.playback(id) } }
}

/** (key, label) for every non-Sonos speaker with a volume, for the scene editor. */
fun AppContainer.otherSceneSpeakers(): List<Pair<String, String>> {
    val nick = settings.nicknames.value
    return sources.filter { it.brand != Brand.SONOS }.flatMap { src ->
        src.summaries.value.speakers.filter { !it.hidden && Capability.VOLUME in it.capabilities }.map { s ->
            val key = SpeakerKey.of(src.brand, s.id)
            key to "${nick[key] ?: s.name} · ${src.brand.displayName}"
        }
    }.sortedBy { it.second.lowercase() }
}
