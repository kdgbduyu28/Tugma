package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.SpeakerSource
import com.timborja.tugma.core.SpeakerSummary
import com.timborja.tugma.sonos.BondRole
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.SonosPlanner
import com.timborja.tugma.sonos.SonosSpeaker
import com.timborja.tugma.upnp.LanAddress
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpeakersViewModel(c: AppContainer) : PlanViewModel(c) {
    val onLan = c.network.onLan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.network.isOnLanNow())
    /** Every non-Sonos brand; each gets its own section in the list. */
    val others: List<SpeakerSource> = c.sources.filter { it.brand != Brand.SONOS }

    /** Brand chip selection; null shows every brand. */
    var filter by mutableStateOf<Brand?>(null)

    init {
        if (c.sources.any { !it.summaries.value.loadedOnce }) refreshAll()
    }

    /** Refreshes every brand at once. */
    fun refreshAll() {
        c.sources.forEach { source -> viewModelScope.launch { source.refresh() } }
    }

    fun pauseAll() {
        viewModelScope.launch {
            val n = c.sonos.pauseAll()
            message = if (n == 0) "Nothing was playing" else "Paused $n ${if (n == 1) "group" else "groups"}"
        }
    }

    val paused = c.sonos.paused
    val hidden = c.settings.hiddenSpeakers
    val nicknames = c.settings.nicknames
    var showHidden by mutableStateOf(false)
    /** Search text; matches name, model or address. */
    var query by mutableStateOf("")

    /** Now-playing lines by coordinator UUID, refreshed while the tab is open. */
    var nowPlaying by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val liveNowPlaying = com.timborja.tugma.data.LiveNowPlaying(c.events, viewModelScope)
    /** Lines from live events, which win over polled ones. */
    val liveLines = liveNowPlaying.lines
    val live = liveNowPlaying.live

    suspend fun pollNowPlaying() {
        liveNowPlaying.watch(c.sonos.coordinatorEventUrls())
        nowPlaying = c.sonos.nowPlaying().mapNotNull { (k, v) -> v.line?.let { k to it } }.toMap()
    }

    fun stopLive() = liveNowPlaying.stop()

    fun resumeAll() {
        viewModelScope.launch {
            val n = c.sonos.resumePaused()
            message = if (n == 0) "Nothing to resume" else "Resumed $n ${if (n == 1) "group" else "groups"}"
        }
    }

    fun addHost(host: String) {
        c.settings.addManualHost(host)
        refreshAll()
    }

    fun startDemo() {
        c.setDemoMode(true)
        refreshAll()
    }

    fun planSeparate(s: SonosSpeaker, h: HouseholdState) {
        viewModelScope.launch {
            val legacy = SonosPlanner.useLegacyPairing(c.sonos.deviceActions(s))
            pending = SonosPlanner.separateStereoPair(s, h.topology.member(s.uuid)?.channelMapSet, legacy)
        }
    }

    fun planRemoveSatellite(bar: SonosSpeaker, satUuid: String, h: HouseholdState) {
        pending = SonosPlanner.removeHtSatellite(bar, satUuid, h.nameOf(satUuid))
    }
}

private val SonosSpeaker.hostLabel: String get() = endpoint.toString()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeakersScreen(
    onPair: (String?) -> Unit,
    onHomeTheater: (String?) -> Unit,
    onGroups: () -> Unit,
    onScenes: () -> Unit,
    onDiagnostics: () -> Unit,
    onMixer: () -> Unit,
    onOpen: (Brand, String) -> Unit,
    onLinkplayGroups: () -> Unit,
) {
    val vm = containerViewModel { SpeakersViewModel(it) }
    val liveLines by vm.liveLines.collectAsState()
    val ui by vm.household.collectAsState()
    val rawOthers = vm.others.map { it.brand to it.summaries.collectAsState().value }
    // Linkplay speakers also answer as DLNA renderers; show them once, under Linkplay.
    val linkplayHosts = rawOthers.firstOrNull { it.first == Brand.LINKPLAY }?.second?.speakers?.map { it.host }?.toSet().orEmpty()
    val hidden by vm.hidden.collectAsState()
    val nicknames by vm.nicknames.collectAsState()
    fun visible(brand: Brand, id: String) = vm.showHidden || "${brand.name}|$id" !in hidden
    val q = vm.query.trim().lowercase()
    fun matches(vararg fields: String?) = q.isEmpty() || fields.any { it?.lowercase()?.contains(q) == true }
    val others = rawOthers.map { (b, st) ->
        val dedup = if (b == Brand.UPNP) st.speakers.filter { it.host !in linkplayHosts } else st.speakers
        b to st.copy(
            speakers = dedup.filter { visible(b, it.id) }
                .map { s -> nicknames["${b.name}|${s.id}"]?.let { s.copy(name = it) } ?: s }
                .filter { matches(it.name, it.model, it.host, it.manufacturer) },
        )
    }
    val onLan by vm.onLan.collectAsState()
    val demo by vm.demo.collectAsState()
    var addHost by remember { mutableStateOf(false) }

    val h = ui.household
    // Now-playing lines: live events where the speakers allow them, plus light polling (slower while
    // events flow). Kept outside the list so scrolling doesn't stop them; both end when the tab closes.
    if (h != null) {
        androidx.compose.runtime.LaunchedEffect(h.fetchedAtMillis) {
            while (true) {
                runCatching { vm.pollNowPlaying() }
                kotlinx.coroutines.delay(if (vm.live.value) 120_000 else 30_000)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopLive() } }
    val counts = (mapOf(Brand.SONOS to (h?.speakers?.count { !it.invisible && visible(Brand.SONOS, it.uuid) && matches(it.roomName, it.modelName, it.host) } ?: 0)) + others.associate { (b, st) -> b to st.speakers.size })
        .filterValues { it > 0 }
    val showSonos = vm.filter == null || vm.filter == Brand.SONOS
    val loading = ui.loading || others.any { it.second.loading }
    // With everything hidden it isn't "nothing found"; the show-hidden button covers that case.
    val nothingFound = counts.isEmpty() && hidden.isEmpty() && q.isEmpty() && ui.loadedOnce && others.all { it.second.loadedOnce } && !loading

    ScreenScaffold(
        title = "Tugma",
        message = vm.message,
        onMessageShown = { vm.message = null },
        actions = {
            IconButton(onClick = { addHost = true }) { Icon(Icons.Outlined.Add, contentDescription = "Add speaker by IP") }
            IconButton(onClick = vm::refreshAll) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = vm::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (demo) item { Banner("Demo household", "Fake speakers for trying things out. Nothing real changes. Turn it off in About.") }
                if (!demo && !onLan) item { Banner("Not on Wi-Fi", "Connect to the same Wi-Fi (or Ethernet) as your speakers. Mobile data can't reach them.", Icons.Outlined.WifiOff) }

                if (counts.size > 1 || vm.filter != null) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = vm.filter == null, onClick = { vm.filter = null }, label = { Text("All") })
                            counts.forEach { (brand, n) ->
                                FilterChip(
                                    selected = vm.filter == brand,
                                    onClick = { vm.filter = if (vm.filter == brand) null else brand },
                                    label = { Text("${brand.displayName} · $n") },
                                )
                            }
                        }
                    }
                }

                if ((h?.speakers?.count { !it.invisible } ?: 0) + rawOthers.sumOf { it.second.speakers.size } > 8 || vm.query.isNotEmpty()) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            singleLine = true,
                            label = { Text("Search speakers") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (showSonos && h != null) {
                    item { SectionLabel("Sonos") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(onClick = { onPair(null) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.SpeakerGroup, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Stereo pair")
                            }
                            FilledTonalButton(onClick = { onHomeTheater(null) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Theaters, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Home theater")
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onGroups, modifier = Modifier.weight(1f)) { Text("Groups") }
                            OutlinedButton(onClick = onScenes, modifier = Modifier.weight(1f)) { Text("Scenes") }
                            val paused by vm.paused.collectAsState()
                            if (paused.isEmpty()) OutlinedButton(onClick = vm::pauseAll, modifier = Modifier.weight(1f)) { Text("Pause all") }
                            else OutlinedButton(onClick = vm::resumeAll, modifier = Modifier.weight(1f)) { Text("Resume") }
                        }
                    }
                    ui.error?.let {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Banner("Refresh failed", it)
                                OutlinedButton(onClick = onDiagnostics) { Text("Run diagnostics") }
                            }
                        }
                    }
                    item {
                        Text(
                            "${h.speakers.count { !it.invisible }} rooms · ${h.speakers.size} players · read from ${h.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(h.speakers.filter { !it.invisible && visible(Brand.SONOS, it.uuid) && matches(it.roomName, it.modelName, it.host) }, key = { it.uuid }) { s ->
                        SpeakerCard(
                            s, h,
                            onOpen = { onOpen(Brand.SONOS, s.uuid) },
                            onSeparate = { vm.planSeparate(s, h) },
                            onRemoveSatellite = { sat -> vm.planRemoveSatellite(s, sat, h) },
                            onPair = { onPair(s.uuid) },
                            onHomeTheater = { onHomeTheater(s.uuid) },
                            nowPlaying = h.coordinatorOf(s.uuid)?.uuid?.let { liveLines[it] ?: vm.nowPlaying[it] },
                        )
                    }
                }

                others.forEach { (brand, st) ->
                    if ((vm.filter == null || vm.filter == brand) && st.speakers.isNotEmpty()) {
                        item(key = "label-$brand") { SectionLabel(sectionTitle(brand)) }
                        item(key = "note-$brand") {
                            Text(sectionNote(brand), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (brand == Brand.LINKPLAY) {
                            item(key = "lp-groups") {
                                FilledTonalButton(onClick = onLinkplayGroups, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.SpeakerGroup, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Groups & stereo pairs")
                                }
                            }
                        }
                        items(st.speakers, key = { "$brand-" + it.id }) { s -> SummaryCard(s, onOpen = { onOpen(brand, s.id) }) }
                    }
                }

                if (q.isNotEmpty() && counts.isEmpty()) {
                    item(key = "no-match") { Text("No speakers match \"${vm.query.trim()}\".", style = MaterialTheme.typography.bodyMedium) }
                }

                if (hidden.isNotEmpty()) {
                    item(key = "hidden-toggle") {
                        TextButton(onClick = { vm.showHidden = !vm.showHidden }) {
                            Text(if (vm.showHidden) "Hide the ${hidden.size} hidden speakers" else "Show ${hidden.size} hidden speakers")
                        }
                    }
                }

                if (counts.values.sum() > 1) {
                    item(key = "mixer") { TextButton(onClick = onMixer) { Text("Volume mixer: every speaker on one screen") } }
                }

                if (nothingFound) {
                    item {
                        EmptyState(
                            title = "No speakers found",
                            body = "Tugma searches your Wi-Fi with SSDP. If nothing shows up, your router may block multicast. Add a speaker by its IP address instead (for Sonos, shown in the Sonos app under About My System).",
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { addHost = true }) { Text("Add by IP") }
                                if (!demo) OutlinedButton(onClick = vm::startDemo) { Text("Try demo") }
                            }
                            TextButton(onClick = onDiagnostics) { Text("Why? Run diagnostics") }
                        }
                    }
                }
            }
        }
    }

    if (addHost) {
        AddHostDialog(onAdd = { vm.addHost(it); addHost = false }, onDismiss = { addHost = false })
    }
    vm.pending?.let { plan ->
        ConfirmPlanDialog(plan, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(s: SpeakerSummary, onOpen: () -> Unit) {
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${s.host}:${s.port}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.manufacturer?.let { Badge(it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
                if (s.model.isNotBlank()) Badge(s.model)
                capabilityLabels(s.capabilities).forEach { Badge(it, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onSurface) }
            }
            s.roleLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun sectionTitle(brand: Brand): String = when (brand) {
    Brand.UPNP -> "Other Wi-Fi speakers · UPnP/DLNA"
    Brand.CAST -> "Google Cast"
    Brand.LINKPLAY -> "Linkplay · Arylic, WiiM, Audio Pro…"
    else -> brand.displayName
}

private fun sectionNote(brand: Brand): String = when (brand) {
    Brand.UPNP -> "Volume and playback only. These makers don't expose pairing over the network."
    Brand.CAST -> "Speakers and displays with Chromecast built-in. Volume and playback only; groups and stereo pairs can only be made in Google Home."
    Brand.LINKPLAY -> "Volume and playback, multiroom groups across models, and experimental left/right stereo pairs."
    else -> ""
}

fun capabilityLabels(caps: Set<Capability>): List<String> = buildList {
    if (Capability.VOLUME in caps) add("Volume")
    if (Capability.MUTE in caps) add("Mute")
    if (Capability.TRANSPORT in caps) add("Play/pause")
    if (Capability.STEREO_PAIR in caps) add("Stereo pair")
    if (Capability.HT_PRIMARY in caps) add("Home theater")
}

@Composable
private fun Banner(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, contentDescription = null)
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerCard(
    s: SonosSpeaker,
    h: HouseholdState,
    onOpen: () -> Unit,
    onSeparate: () -> Unit,
    onRemoveSatellite: (String) -> Unit,
    onPair: () -> Unit,
    onHomeTheater: () -> Unit,
    nowPlaying: String? = null,
) {
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.roomName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(s.hostLabel, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(s.modelName ?: "Unknown model", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                if (s.isSoundbar) Badge("Soundbar")
                if (s.isSub) Badge("Sub")
                if (s.isGroupCoordinator && (h.topology.groupOf(s.uuid)?.members?.count { !it.invisible } ?: 0) > 1) Badge("Group coordinator")
                if (!s.isGroupCoordinator) h.topology.groupOf(s.uuid)?.let { g -> Badge("Grouped with ${h.nameOf(g.coordinator)}") }
            }
            Text(s.uuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.describeError?.let { Text("Couldn't read model: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            s.roleLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            nowPlaying?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1) }

            val bonded = bondedMembers(s, h)
            if (bonded.isNotEmpty()) {
                HorizontalDivider()
                bonded.forEach { (label, other) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$label: ${other.modelName ?: "?"}", style = MaterialTheme.typography.bodyMedium)
                            Text("${other.hostLabel} · ${other.uuid}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.role is BondRole.HtPrimary) {
                            TextButton(onClick = { onRemoveSatellite(other.uuid) }) { Text("Remove") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.role) {
                    is BondRole.StereoLeft -> OutlinedButton(onClick = onSeparate) {
                        Icon(Icons.AutoMirrored.Outlined.CallSplit, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Separate pair")
                    }
                    is BondRole.HtPrimary -> OutlinedButton(onClick = onHomeTheater) { Text("Edit home theater") }
                    BondRole.Standalone -> {
                        if (s.isSoundbar) OutlinedButton(onClick = onHomeTheater) { Text("Set up home theater") }
                        if (!s.isSub) OutlinedButton(onClick = onPair) { Text("Pair…") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Hidden members bonded to a visible speaker, with a short label for each. */
private fun bondedMembers(s: SonosSpeaker, h: HouseholdState): List<Pair<String, SonosSpeaker>> = when (val r = s.role) {
    is BondRole.StereoLeft -> listOfNotNull(h.speaker(r.partner)?.let { "Right" to it }, r.sub?.let(h::speaker)?.let { "Sub" to it })
    is BondRole.HtPrimary -> r.satellites.mapNotNull { a ->
        h.speaker(a.uuid)?.let { com.timborja.tugma.sonos.BondRoles.channelLabel(a.channelSet.joinToString(",")).replaceFirstChar(Char::uppercase) to it }
    }
    is BondRole.WithSub -> listOfNotNull(h.speaker(r.sub)?.let { "Sub" to it })
    else -> emptyList()
}

@Composable
private fun AddHostDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val host = text.trim()
    val valid = host.isNotEmpty() && LanAddress.isLocal(host.substringBefore(':'))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add speaker by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For Sonos, any one speaker is enough; Tugma reads the rest of the household from it. Linkplay and Google Cast speakers are added one address at a time.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    isError = host.isNotEmpty() && !valid,
                    supportingText = { if (host.isNotEmpty() && !valid) Text("Must be a local network address") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(host) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
