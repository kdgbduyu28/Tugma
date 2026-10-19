package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.HomeTheaterRules
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.HtSlot
import com.timborja.tugma.sonos.SonosPlanner
import com.timborja.tugma.sonos.SonosSpeaker
import com.timborja.tugma.sonos.blocked
import com.timborja.tugma.sonos.warningTexts

class HomeTheaterViewModel(c: AppContainer, initialBar: String?) : PlanViewModel(c) {
    var barId by mutableStateOf(initialBar)
        private set
    /** Slot → speaker UUID, starting from the soundbar's current satellites. */
    val assigned = mutableStateMapOf<HtSlot, String>()
    private var needsSync = true

    val experimental get() = c.settings.experimentalFronts

    fun selectBar(uuid: String?, h: HouseholdState) {
        barId = uuid
        needsSync = true
        sync(h)
    }

    /** Resets assignments to the soundbar's current satellites: when the bar changes and after each write. */
    fun sync(h: HouseholdState) {
        if (!needsSync) return
        needsSync = false
        assigned.clear()
        val bar = barId?.let(h::speaker) ?: return
        HomeTheaterRules.existing(bar, h).forEach { (slot, s) -> assigned[slot] = s.uuid }
    }

    override fun onExecuted() {
        // execute() has already re-read topology, so sync against it right away.
        needsSync = true
        c.sonos.state.value.household?.let(::sync)
    }

    fun slots(h: HouseholdState): Map<HtSlot, SonosSpeaker> =
        assigned.mapNotNull { (slot, uuid) -> h.speaker(uuid)?.let { slot to it } }.toMap()

    fun review(h: HouseholdState) {
        val bar = barId?.let(h::speaker) ?: return
        val slots = slots(h)
        val issues = HomeTheaterRules.check(bar, slots, h, experimental.value)
        if (issues.blocked) return
        pending = SonosPlanner.addHtSatellites(bar, HomeTheaterRules.channelMap(bar, slots), HomeTheaterRules.summary(bar, slots), issues.warningTexts)
    }

    fun planRemove(slot: HtSlot, h: HouseholdState) {
        val bar = barId?.let(h::speaker) ?: return
        val existing = HomeTheaterRules.existing(bar, h)[slot] ?: return
        pending = SonosPlanner.removeHtSatellite(bar, existing.uuid, existing.roomName)
    }
}

@Composable
fun HomeTheaterScreen(initialBar: String?, onBack: () -> Unit) {
    val vm = containerViewModel(key = "ht-$initialBar") { HomeTheaterViewModel(it, initialBar) }
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    val experimental by vm.experimental.collectAsState()

    ScreenScaffold("Home theater", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        val h = ui.household
        if (h == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Load your speakers first") }
            return@ScreenScaffold
        }
        LaunchedEffect(vm.barId) { vm.sync(h) }

        val soundbars = h.speakers.filter { it.isSoundbar && !it.invisible }
        val bar = vm.barId?.let(h::speaker)
        val existing = bar?.let { HomeTheaterRules.existing(it, h) }.orEmpty()
        val candidates = bar?.let { HomeTheaterRules.candidates(it, h) }.orEmpty()
        val slots = vm.slots(h)
        val issues = HomeTheaterRules.check(bar, slots, h, experimental)

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add any speakers to a soundbar as surrounds or sub, including models the official app won't allow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (soundbars.isEmpty()) {
                IssueList(listOf(com.timborja.tugma.sonos.Issue(com.timborja.tugma.sonos.Severity.BLOCK, "No soundbar or Amp found in this household.")))
            }
            SpeakerPicker("Soundbar", bar, soundbars, { vm.selectBar(it?.uuid, h) })

            if (bar != null) {
                SectionLabel("Satellites")
                HtSlot.entries.filter { !it.experimental || experimental }.forEach { slot ->
                    val current = existing[slot]
                    if (current != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpeakerPicker(slot.label, current, emptyList(), {}, Modifier.weight(1f), enabled = false)
                            OutlinedButton(onClick = { vm.planRemove(slot, h) }) { Text("Remove") }
                        }
                    } else {
                        val taken = vm.assigned.filterKeys { it != slot }.values.toSet()
                        SpeakerPicker(
                            label = slot.label + if (slot.experimental) " (experimental)" else "",
                            selected = vm.assigned[slot]?.let(h::speaker),
                            options = candidates.filter { it.uuid !in taken && it.uuid !in existing.values.map { e -> e.uuid } },
                            onSelect = { s -> if (s == null) vm.assigned.remove(slot) else vm.assigned[slot] = s.uuid },
                            none = "None",
                        )
                    }
                }
                if (!experimental) {
                    Text(
                        "Front satellites are hidden. Turn on experimental fronts in About to try them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IssueList(issues)
                SectionLabel("ChannelMapSet preview")
                MonoBlock(HomeTheaterRules.channelMap(bar, slots).replace(";", ";\n"))
                Text(
                    "Sent to ${bar.roomName} (${bar.endpoint}) as DeviceProperties · AddHTSatellite. The map lists every satellite, old and new.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.review(h) }, enabled = !issues.blocked && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.busy) "Working…" else "Review and apply")
                }
            }
        }
    }

    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}
