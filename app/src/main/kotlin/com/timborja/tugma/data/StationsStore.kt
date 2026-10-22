package com.timborja.tugma.data

import android.content.Context
import androidx.core.content.edit
import com.timborja.tugma.core.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Links the user saved (internet radio, audio files), shared by every brand's "Play a link". */
class StationsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stations", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Station.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private val _stations = MutableStateFlow(load())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    fun add(station: Station) = save(_stations.value.filter { it.url != station.url } + station)
    fun remove(station: Station) = save(_stations.value - station)
    fun replaceAll(list: List<Station>) = save(list.distinctBy { it.url })

    private fun save(list: List<Station>) {
        _stations.value = list
        prefs.edit { putString("list", json.encodeToString(serializer, list)) }
    }

    private fun load(): List<Station> =
        prefs.getString("list", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
