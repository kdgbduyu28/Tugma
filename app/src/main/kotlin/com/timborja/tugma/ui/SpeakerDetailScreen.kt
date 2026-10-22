package com.timborja.tugma.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.SourceState
import com.timborja.tugma.core.TransportState
import com.timborja.tugma.remote.RemoteService
import com.timborja.tugma.remote.VolumeKeys
import com.timborja.tugma.widget.SpeakerWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SpeakerDetailViewModel(private val c: AppContainer, val brand: Brand, val id: String) : ViewModel() {
    private val source = c.source(brand)
    val state: StateFlow<SourceState> = source?.summaries ?: MutableStateFlow(SourceState())

    var status by mutableStateOf<PlaybackStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    /** Slider position while the user drags, so polling doesn't fight the thumb. */
    var dragVolume by mutableStateOf<Float?>(null)

    private fun control(): PlaybackControl? = source?.playback(id)

    /** True while UPnP events are arriving; the screen then polls much less often. */
    var live by mutableStateOf(false)
        private set
    private val subs = java.util.concurrent.CopyOnWriteArrayList<com.timborja.tugma.upnp.gena.Subscription>()

    init {
        startLive()
    }

    /** Subscribes to AVTransport and RenderingControl events (Sonos and DLNA, real speakers only), renewing until the screen closes. */
    private fun startLive() {
        val urls = when (brand) {
            Brand.SONOS -> c.sonos.eventUrls(id)?.toList().orEmpty()
            Brand.UPNP -> c.dlna.eventUrls(id)
            else -> emptyList()
        }
        if (urls.isEmpty()) return
        viewModelScope.launch {
            val events = c.events.events() ?: return@launch
            val sids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            // Collect before subscribing so the speaker's initial event isn't missed.
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                events.collect { e -> if (e.sid in sids) applyEvent(e.properties) }
            }
            urls.mapNotNull { c.events.subscribe(it) }.forEach { subs += it; sids += it.sid }
            live = subs.isNotEmpty()
            while (live) {
                delay((subs.minOf { it.timeoutSeconds } - 60).coerceAtLeast(30) * 1000L)
                val renewed = subs.map { c.events.renew(it) }
                if (renewed.any { it == null }) {
                    live = false // fall back to polling
                } else {
                    subs.clear()
                    subs.addAll(renewed.filterNotNull())
                }
            }
        }
    }

    private fun applyEvent(properties: Map<String, String>) {
        val change = properties["LastChange"]?.let(com.timborja.tugma.upnp.gena.GenaParser::lastChange) ?: return
        var next = status ?: PlaybackStatus(volume = null, maxVolume = 100, muted = null, transport = TransportState.UNKNOWN)
        change["TransportState"]?.let { next = next.copy(transport = com.timborja.tugma.upnp.dlna.UpnpPlaybackControl.parseTransport(it)) }
        change["CurrentTrackMetaData"]?.let(com.timborja.tugma.upnp.dlna.Didl::parse)?.let { t -> next = next.copy(title = t.title, artist = t.artist) }
        if (dragVolume == null) change["Volume"]?.toIntOrNull()?.let { next = next.copy(volume = it) }
        change["Mute"]?.let { next = next.copy(muted = it == "1") }
        status = next
    }

    override fun onCleared() {
        val toCancel = subs.toList()
        subs.clear()
        // viewModelScope is already cancelled; unsubscribing is best-effort.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { toCancel.forEach { c.events.unsubscribe(it) } }
    }

    suspend fun poll() {
        val control = control() ?: return
        runCatching { control.status() }
            .onSuccess { status = it; error = null }
            .onFailure { error = it.message ?: "Couldn't reach the speaker" }
    }

    fun act(label: String, block: suspend PlaybackControl.() -> Unit) {
        val control = control() ?: return
        viewModelScope.launch {
            runCatching { control.block() }.onFailure { message = "$label failed: ${it.message}" }
            poll()
        }
    }

    /** Volume buttons: steps of 2 on 0–100 speakers, 1 on small ranges. */
    fun stepVolume(up: Boolean): Boolean {
        val s = status ?: return false
        val v = s.volume ?: return false
        val step = if (s.maxVolume >= 50) 2 else 1
        val next = (v + if (up) step else -step).coerceIn(0, s.maxVolume)
        status = s.copy(volume = next)
        act("Volume") { setVolume(next) }
        return true
    }

    val hidden = c.settings.hiddenSpeakers
    fun setHidden(on: Boolean) = c.settings.setHidden("${brand.name}|$id", on)

    val nicknames = c.settings.nicknames
    /** Local names only for brands Tugma can't rename on the device itself. */
    val canNickname: Boolean get() = brand == Brand.UPNP || brand == Brand.CAST
    fun setNickname(name: String?) = c.settings.setNickname("${brand.name}|$id", name)

    fun commitVolume() {
        val v = dragVolume ?: return
        act("Volume") { setVolume(v.toInt()) }
        dragVolume = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerDetailScreen(
    brand: Brand,
    id: String,
    onBack: () -> Unit,
    onPair: (String) -> Unit,
    onHomeTheater: (String) -> Unit,
    onGroups: () -> Unit,
    onAlarms: () -> Unit,
) {
    val vm = containerViewModel(key = "detail-$brand-$id") { SpeakerDetailViewModel(it, brand, id) }
    val state by vm.state.collectAsState()
    val nicknames by vm.nicknames.collectAsState()
    val speaker = state.speakers.firstOrNull { it.id == id }?.let { s -> nicknames["${brand.name}|$id"]?.let { s.copy(name = it) } ?: s }
    var nicknaming by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val remote by RemoteService.active.collectAsState()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // While this screen is open, the phone's volume buttons control this speaker.
    androidx.compose.runtime.DisposableEffect(id) {
        val handler: (Boolean) -> Boolean = { up -> vm.stepVolume(up) }
        VolumeKeys.set(handler)
        onDispose { VolumeKeys.clear(handler) }
    }

    // Status is polled while the screen is open; slowly, because every poll shows up in the debug log.
    LaunchedEffect(id) {
        while (true) {
            vm.poll()
            delay(if (vm.live) 30_000 else 10_000)
        }
    }

    ScreenScaffold(
        title = speaker?.name ?: "Speaker",
        onBack = onBack,
        message = vm.message,
        onMessageShown = { vm.message = null },
        actions = { IconButton(onClick = { vm.act("Refresh") {} }) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh status") } },
    ) { padding ->
        if (speaker == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This speaker isn't in the latest scan. Go back and refresh.")
            }
            return@ScreenScaffold
        }
        val caps = speaker.capabilities
        val status = vm.status
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Badge(speaker.brand.displayName, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        speaker.manufacturer?.takeIf { it != speaker.brand.displayName }?.let { Badge(it) }
                        if (speaker.model.isNotBlank()) Badge(speaker.model)
                    }
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Text(
                        "${speaker.host}:${speaker.port}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClickLabel = "Copy address") {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(speaker.host))
                            vm.message = "Copied ${speaker.host}"
                        },
                    )
                    Text(speaker.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    speaker.roleLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("Supports: " + capabilityLabels(caps).joinToString(", ").ifEmpty { "nothing Tugma can control yet" }, style = MaterialTheme.typography.bodySmall)
                    val hiddenSet by vm.hidden.collectAsState()
                    val isHidden = "${brand.name}|$id" in hiddenSet
                    Row {
                        androidx.compose.material3.TextButton(onClick = { vm.setHidden(!isHidden) }) {
                            Text(if (isHidden) "Show in Speakers list" else "Hide from Speakers list")
                        }
                        if (vm.canNickname) androidx.compose.material3.TextButton(onClick = { nicknaming = true }) { Text("Nickname…") }
                    }
                }
            }

            vm.error?.let { IssueList(listOf(com.timborja.tugma.sonos.Issue(com.timborja.tugma.sonos.Severity.WARN, "Couldn't read status: $it"))) }

            if (Capability.TRANSPORT in caps) {
                SectionLabel("Now playing")
                Text(status?.title ?: transportLabel(status?.transport), style = MaterialTheme.typography.titleMedium)
                status?.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (status?.title != null) Text(transportLabel(status.transport), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.act("Previous") { previous() } }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous") }
                    val playing = status?.transport == TransportState.PLAYING
                    FilledIconButton(onClick = { vm.act(if (playing) "Pause" else "Play") { if (playing) pause() else play() } }, modifier = Modifier.size(56.dp)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { vm.act("Stop") { stop() } }) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
                    IconButton(onClick = { vm.act("Next") { next() } }) { Icon(Icons.Filled.SkipNext, contentDescription = "Next") }
                }
                if (speaker.brand == Brand.SONOS || speaker.model == com.timborja.tugma.cast.CastDevice.GROUP_MODEL) {
                    Text(
                        "Playback controls the whole group this speaker belongs to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (Capability.VOLUME in caps) {
                SectionLabel("Volume")
                val max = status?.maxVolume ?: 100
                val current = vm.dragVolume ?: status?.volume?.toFloat() ?: 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (Capability.MUTE in caps) {
                        val muted = status?.muted == true
                        IconButton(onClick = { vm.act("Mute") { setMuted(!muted) } }) {
                            Icon(
                                if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = if (muted) "Unmute" else "Mute",
                            )
                        }
                    }
                    Slider(
                        value = current.coerceIn(0f, max.toFloat()),
                        onValueChange = { vm.dragVolume = it },
                        onValueChangeFinished = vm::commitVolume,
                        valueRange = 0f..max.toFloat(),
                        enabled = status?.volume != null,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "${speaker.name} volume" },
                    )
                    Text(
                        status?.volume?.let { "${current.toInt()}" } ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(40.dp).padding(start = 8.dp),
                    )
                }
                Text("Your phone's volume buttons control this speaker while this screen is open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (max != 100) {
                    Text("This speaker's volume runs 0–$max.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (Capability.VOLUME in caps || Capability.TRANSPORT in caps) {
                SectionLabel("Remote")
                val controlling = remote?.brand == brand && remote?.id == id
                Text(
                    if (controlling) "The notification and your phone's volume buttons control ${speaker.name}."
                    else "Put ${speaker.name} in the notification shade and lock screen; the volume buttons then change its volume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    if (controlling) {
                        RemoteService.stop(context)
                    } else {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        RemoteService.start(context, brand, id, speaker.name)
                    }
                }) { Text(if (controlling) "Stop notification control" else "Control from notification") }
                var onWidget by remember(id) { mutableStateOf(SpeakerWidget.isTarget(context, brand, id)) }
                OutlinedButton(onClick = { SpeakerWidget.setTarget(context, brand, id, speaker.name); onWidget = true }, enabled = !onWidget) {
                    Text(if (onWidget) "On the home-screen widget" else "Show on widget")
                }
            }

            if (speaker.brand == Brand.SONOS && (Capability.STEREO_PAIR in caps || Capability.HT_PRIMARY in caps)) {
                SectionLabel("Bonding")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Capability.STEREO_PAIR in caps) OutlinedButton(onClick = { onPair(id) }) { Text("Stereo pair…") }
                    if (Capability.HT_PRIMARY in caps) OutlinedButton(onClick = { onHomeTheater(id) }) { Text("Home theater…") }
                }
            }

            PlayLinkSection(brand, id, onMessage = { vm.message = it })

            if (speaker.brand != Brand.SONOS && Capability.TRANSPORT in caps) PhoneSleepTimerSection(brand, id)

            if (speaker.brand == Brand.CAST) {
                OutlinedButton(onClick = {
                    vm.act("Stop casting") { (this as? com.timborja.tugma.cast.CastPlayback)?.stopCasting() }
                }) { Text("Stop casting") }
            }

            if (speaker.brand == Brand.LINKPLAY) LinkplayExtrasSection(id, onMessage = { vm.message = it })

            if (speaker.brand == Brand.SONOS && !speaker.hidden) {
                SectionLabel("Group")
                Text(
                    speaker.roleLabel?.let { "$it. " }.orEmpty() + "Play this room in sync with others, or set the whole group's volume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SonosGroupRow(id, onMessage = { vm.message = it })
                OutlinedButton(onClick = onGroups) { Text("Groups…") }
                SonosExtrasSection(id, onAlarms = onAlarms, onMessage = { vm.message = it })
                SonosSoundSection(id, homeTheater = Capability.HT_PRIMARY in caps)
                SonosSettingsSection(id, onMessage = { vm.message = it })
            }
        }
    }

    if (nicknaming) {
        var text by remember { mutableStateOf(nicknames["${brand.name}|$id"].orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { nicknaming = false },
            title = { Text("Nickname") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    label = { Text("Shown instead of the device's own name") },
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.setNickname(text); nicknaming = false }) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.setNickname(null); nicknaming = false }) { Text("Clear") } },
        )
    }
}

private fun transportLabel(t: TransportState?): String = when (t) {
    TransportState.PLAYING -> "Playing"
    TransportState.PAUSED -> "Paused"
    TransportState.STOPPED -> "Stopped"
    TransportState.NO_MEDIA -> "Nothing queued"
    TransportState.TRANSITIONING -> "Loading…"
    TransportState.UNKNOWN, null -> "Status unknown"
}
