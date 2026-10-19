package com.timborja.tugma.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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

    fun setDemoMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        _demoMode.value = on
    }

    fun setExperimentalFronts(on: Boolean) {
        prefs.edit().putBoolean(KEY_FRONTS, on).apply()
        _experimentalFronts.value = on
    }

    fun addManualHost(host: String) = setHosts(_manualHosts.value + host)
    fun removeManualHost(host: String) = setHosts(_manualHosts.value - host)

    private fun setHosts(hosts: Set<String>) {
        prefs.edit().putStringSet(KEY_HOSTS, hosts).apply()
        _manualHosts.value = hosts
    }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_FRONTS = "experimental_fronts"
        const val KEY_HOSTS = "manual_hosts"
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
