package com.timborja.tugma.data

import android.content.Context
import android.net.ConnectivityManager
import com.timborja.tugma.upnp.HttpTransport
import com.timborja.tugma.upnp.LanAddress
import com.timborja.tugma.upnp.gena.GenaClient
import com.timborja.tugma.upnp.gena.GenaEvent
import com.timborja.tugma.upnp.gena.GenaServer
import com.timborja.tugma.upnp.gena.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address

/**
 * UPnP event subscriptions for the whole app. One small NOTIFY server is started on first use
 * and bound to the phone's Wi-Fi address; screens subscribe to the services they show.
 * Anything that fails here just means the screen keeps polling.
 */
class EventHub(private val context: Context, transport: HttpTransport) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = GenaClient(transport)
    private val mutex = Mutex()
    private var server: GenaServer? = null
    private var host: String? = null

    /** The phone's private IPv4 address on the active network, which speakers call back. */
    private fun localAddress(): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && LanAddress.isLocal(it.hostAddress.orEmpty()) }
    }

    private suspend fun ensureServer(): Pair<GenaServer, String>? = mutex.withLock {
        val addr = localAddress() ?: return@withLock null
        val ip = addr.hostAddress ?: return@withLock null
        val existing = server
        if (existing != null && host == ip) return@withLock existing to ip
        existing?.stop()
        val s = GenaServer(addr)
        runCatching { s.start(scope) }.onFailure { return@withLock null }
        server = s
        host = ip
        s to ip
    }

    /** Every NOTIFY the server receives; collect it before subscribing so the initial event isn't missed. */
    suspend fun events(): Flow<GenaEvent>? = ensureServer()?.first?.events

    /** Null when the phone has no LAN address or the speaker refused. */
    suspend fun subscribe(eventUrl: String): Subscription? {
        val (s, ip) = ensureServer() ?: return null
        return runCatching { client.subscribe(eventUrl, s.callbackUrl(ip)) }.getOrNull()
    }

    suspend fun renew(sub: Subscription): Subscription? = runCatching { client.renew(sub) }.getOrNull()

    suspend fun unsubscribe(sub: Subscription) = client.unsubscribe(sub)
}
