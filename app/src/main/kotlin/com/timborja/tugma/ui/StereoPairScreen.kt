package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.BondRole
import com.timborja.tugma.sonos.ChannelMap
import com.timborja.tugma.sonos.HouseholdState
import com.timborja.tugma.sonos.SonosPlanner
import com.timborja.tugma.sonos.StereoPairRules
import com.timborja.tugma.sonos.blocked
import com.timborja.tugma.sonos.warningTexts
import kotlinx.coroutines.launch

class StereoPairViewModel(c: AppContainer, initialLeft: String?) : PlanViewModel(c) {
    var left by mutableStateOf(initialLeft)
    var right by mutableStateOf<String?>(null)

    fun swap() {
        val l = left
        left = right
        right = l
    }

    fun review(h: HouseholdState) {
        val l = left?.let(h::speaker) ?: return
        val r = right?.let(h::speaker) ?: return
        val issues = StereoPairRules.check(l, r, h)
        if (issues.blocked) return
        viewModelScope.launch {
            val actions = c.sonos.deviceActions(l)
            val legacy = SonosPlanner.useLegacyPairing(actions)
            val extra = buildList {
                if (legacy) add("This firmware doesn't list AddBondedZones, so Tugma will use the older CreateStereoPair action.")
                if (actions == null) add("Couldn't read the speaker's action list; sending AddBondedZones.")
            }
            pending = SonosPlanner.stereoPair(l, r, legacy, issues.warningTexts + extra)
        }
    }
}

@Composable
fun StereoPairScreen(initialLeft: String?, onBack: () -> Unit) {
    val vm = containerViewModel(key = "pair-$initialLeft") { StereoPairViewModel(it, initialLeft) }
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    LaunchedEffect(vm.succeeded) { if (vm.succeeded) onBack() }

    ScreenScaffold("Stereo pair", onBack = onBack, message = vm.message, onMessageShown = { vm.message = null }) { padding ->
        val h = ui.household
        if (h == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Load your speakers first", style = MaterialTheme.typography.bodyLarge)
            }
            return@ScreenScaffold
        }
        val options = h.speakers.filter { !it.invisible && it.role != BondRole.Bridge }
        val l = vm.left?.let(h::speaker)
        val r = vm.right?.let(h::speaker)
        val issues = StereoPairRules.check(l, r, h)

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pick any two speakers, even different models. The left speaker receives the command and becomes the pair's coordinator.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SpeakerPicker("Left speaker", l, options.filter { it.uuid != vm.right }, { vm.left = it?.uuid })
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(onClick = vm::swap) { Icon(Icons.Outlined.SwapVert, contentDescription = "Swap left and right") }
            }
            SpeakerPicker("Right speaker", r, options.filter { it.uuid != vm.left }, { vm.right = it?.uuid })

            if (l != null || r != null) IssueList(issues)

            if (l != null && r != null && l.uuid != r.uuid) {
                SectionLabel("ChannelMapSet preview")
                MonoBlock(ChannelMap.stereoPair(l.uuid, r.uuid))
                Text(
                    "Sent to ${l.roomName} (${l.endpoint}) as DeviceProperties · AddBondedZones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = { vm.review(h) }, enabled = !issues.blocked && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).then(Modifier), strokeWidth = 2.dp)
                Text(if (vm.busy) "Pairing…" else "Review and pair")
            }
        }
    }

    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}
