package com.timborja.tugma.data

import com.timborja.tugma.core.TransportState
import com.timborja.tugma.upnp.dlna.Didl
import com.timborja.tugma.upnp.dlna.NowPlaying
import com.timborja.tugma.upnp.dlna.UpnpPlaybackControl
import com.timborja.tugma.upnp.gena.GenaParser
import com.timborja.tugma.upnp.gena.Subscription
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Live now-playing for a set of group coordinators via AVTransport events. [lines] holds what
 * events have told us so far, by coordinator UUID; callers fall back to polling for the rest.
 */
class LiveNowPlaying(private val events: EventHub, private val scope: CoroutineScope) {
    private val _lines = MutableStateFlow<Map<String, String>>(emptyMap())
    val lines: StateFlow<Map<String, String>> = _lines.asStateFlow()

    private val _live = MutableStateFlow(false)
    /** True while at least one subscription is active. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private var job: Job? = null
    private var watching: Map<String, String> = emptyMap()

    /** [coordinators]: coordinator UUID → its AVTransport event URL. Restarts only when the set changes. */
    fun watch(coordinators: Map<String, String>) {
        if (coordinators == watching && job?.isActive == true) return
        stop()
        watching = coordinators
        if (coordinators.isEmpty()) return
        job = scope.launch {
            val flow = events.events() ?: return@launch
            val bySid = ConcurrentHashMap<String, String>()
            val state = ConcurrentHashMap<String, NowPlaying>()
            // Collect before subscribing so each speaker's initial event isn't missed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                flow.collect { e ->
                    val uuid = bySid[e.sid] ?: return@collect
                    val change = e.properties["LastChange"]?.let(GenaParser::lastChange) ?: return@collect
                    val prev = state[uuid] ?: NowPlaying(TransportState.UNKNOWN, null, null)
                    val track = change["CurrentTrackMetaData"]?.let(Didl::parse)
                    val next = prev.copy(
                        transport = change["TransportState"]?.let(UpnpPlaybackControl::parseTransport) ?: prev.transport,
                        title = if (change.containsKey("CurrentTrackMetaData")) track?.title else prev.title,
                        artist = if (change.containsKey("CurrentTrackMetaData")) track?.artist else prev.artist,
                    )
                    state[uuid] = next
                    _lines.update { m -> next.line?.let { m + (uuid to it) } ?: (m - uuid) }
                }
            }
            var subs: List<Subscription> = coordinators.mapNotNull { (uuid, url) -> events.subscribe(url)?.also { bySid[it.sid] = uuid } }
            _live.value = subs.isNotEmpty()
            try {
                while (isActive && subs.isNotEmpty()) {
                    delay((subs.minOf { it.timeoutSeconds } - 60).coerceAtLeast(30) * 1000L)
                    subs = subs.mapNotNull { s -> events.renew(s) ?: null.also { bySid.remove(s.sid) } }
                    _live.value = subs.isNotEmpty()
                }
            } finally {
                _live.value = false
                withContext(NonCancellable) { subs.forEach { events.unsubscribe(it) } }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watching = emptyMap()
        _lines.value = emptyMap()
    }
}
