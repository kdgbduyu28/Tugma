package com.timborja.tugma.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.edit
import com.timborja.tugma.sonos.TopologySnapshot
import com.timborja.tugma.upnp.Discoverer
import com.timborja.tugma.upnp.SsdpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.io.File

/** Simple preferences, exposed as flows so screens react to changes. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _demoMode = MutableStateFlow(prefs.getBoolean(KEY_DEMO, false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _experimentalFronts = MutableStateFlow(prefs.getBoolean(KEY_FRONTS, false))
    val experimentalFronts: StateFlow<Boolean> = _experimentalFronts.asStateFlow()

    private val _manualHosts = MutableStateFlow(prefs.getStringSet(KEY_HOSTS, emptySet()).orEmpty().toSet())
    val manualHosts: StateFlow<Set<String>> = _manualHosts.asStateFlow()

    private val _hidden = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet())
    /** Speakers the user hid from the list, as "BRAND|id". */
    val hiddenSpeakers: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _nicknames = MutableStateFlow(decodeNicknames(prefs.getStringSet(KEY_NICKNAMES, emptySet()).orEmpty()))
    /** Names the user gave speakers whose brand can't be renamed (DLNA, Cast), keyed "BRAND|id". */
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    fun setNickname(key: String, name: String?) {
        val clean = name?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        replaceNicknames(if (clean == null) _nicknames.value - key else _nicknames.value + (key to clean))
    }

    fun replaceNicknames(map: Map<String, String>) {
        prefs.edit { putStringSet(KEY_NICKNAMES, map.map { (k, v) -> "$k\t$v" }.toSet()) }
        _nicknames.value = map
    }

    private fun decodeNicknames(set: Set<String>): Map<String, String> =
        set.mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    fun replaceHidden(keys: Set<String>) {
        prefs.edit { putStringSet(KEY_HIDDEN, keys) }
        _hidden.value = keys
    }

    fun setHidden(key: String, hidden: Boolean) {
        val next = if (hidden) _hidden.value + key else _hidden.value - key
        prefs.edit { putStringSet(KEY_HIDDEN, next) }
        _hidden.value = next
    }

    private val _theme = MutableStateFlow(
        runCatching { com.timborja.tugma.ui.ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }.getOrDefault(com.timborja.tugma.ui.ThemeMode.SYSTEM),
    )
    val theme: StateFlow<com.timborja.tugma.ui.ThemeMode> = _theme.asStateFlow()

    private val _wallpaperColors = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val wallpaperColors: StateFlow<Boolean> = _wallpaperColors.asStateFlow()

    fun setTheme(mode: com.timborja.tugma.ui.ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _theme.value = mode
    }

    fun setWallpaperColors(on: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, on) }
        _wallpaperColors.value = on
    }

    private val _welcomed = MutableStateFlow(prefs.getBoolean(KEY_WELCOMED, false))
    /** False until the first-run welcome screen is dismissed. */
    val welcomed: StateFlow<Boolean> = _welcomed.asStateFlow()

    fun setWelcomed() {
        prefs.edit { putBoolean(KEY_WELCOMED, true) }
        _welcomed.value = true
    }

    fun setDemoMode(on: Boolean) {
        prefs.edit { putBoolean(KEY_DEMO, on) }
        _demoMode.value = on
    }

    fun setExperimentalFronts(on: Boolean) {
        prefs.edit { putBoolean(KEY_FRONTS, on) }
        _experimentalFronts.value = on
    }

    fun addManualHost(host: String) = setHosts(_manualHosts.value + host)
    fun removeManualHost(host: String) = setHosts(_manualHosts.value - host)
    fun replaceManualHosts(hosts: Set<String>) = setHosts(hosts)

    private fun setHosts(hosts: Set<String>) {
        prefs.edit { putStringSet(KEY_HOSTS, hosts) }
        _manualHosts.value = hosts
    }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_FRONTS = "experimental_fronts"
        const val KEY_HOSTS = "manual_hosts"
        const val KEY_HIDDEN = "hidden_speakers"
        const val KEY_NICKNAMES = "nicknames"
        const val KEY_THEME = "theme"
        const val KEY_WELCOMED = "welcomed"
        const val KEY_DYNAMIC = "wallpaper_colors"
    }
}

/** True while the default network is Wi-Fi or Ethernet, i.e. speakers could be reachable. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isOnLanNow(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork).isLan()

    val onLan: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.isLan())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(isOnLanNow())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun NetworkCapabilities?.isLan(): Boolean =
        this != null && (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
}

/** Holds a Wi-Fi MulticastLock for the duration of an SSDP search; many phones drop multicast otherwise. */
class MulticastLockDiscoverer(context: Context, private val delegate: Discoverer) : Discoverer {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override suspend fun search(): List<SsdpResponse> {
        val lock = wifi.createMulticastLock("tugma-ssdp").apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            return delegate.search()
        } finally {
            lock.release()
        }
    }
}

/** Snapshots as JSON files in app storage, newest first. */
class SnapshotStore(context: Context) {
    private val dir = File(context.filesDir, "snapshots").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<TopologySnapshot>> = _all.asStateFlow()

    fun save(snapshot: TopologySnapshot) {
        File(dir, "${snapshot.id}.json").writeText(json.encodeToString(TopologySnapshot.serializer(), snapshot))
        _all.value = load()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _all.value = load()
    }

    fun get(id: String): TopologySnapshot? = _all.value.firstOrNull { it.id == id }

    private fun load(): List<TopologySnapshot> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(TopologySnapshot.serializer(), it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMillis }
}
