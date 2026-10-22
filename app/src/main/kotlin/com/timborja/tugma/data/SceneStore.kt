package com.timborja.tugma.data

import android.content.Context
import androidx.core.content.edit
import com.timborja.tugma.sonos.SonosScene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Saved Sonos scenes. Kept on the phone; the speakers know nothing about them. */
class SceneStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("scenes", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(SonosScene.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private val _scenes = MutableStateFlow(load())
    val scenes: StateFlow<List<SonosScene>> = _scenes.asStateFlow()

    fun save(scene: SonosScene) = write(_scenes.value.filter { it.id != scene.id } + scene)
    fun delete(id: String) = write(_scenes.value.filter { it.id != id })
    fun replaceAll(list: List<SonosScene>) = write(list.distinctBy { it.id })

    private fun write(list: List<SonosScene>) {
        _scenes.value = list
        prefs.edit { putString("list", json.encodeToString(serializer, list)) }
        SceneReminders.reschedule(context, list)
    }

    private fun load(): List<SonosScene> =
        prefs.getString("list", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
