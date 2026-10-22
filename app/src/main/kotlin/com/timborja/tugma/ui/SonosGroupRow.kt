package com.timborja.tugma.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.timborja.tugma.AppContainer
import com.timborja.tugma.sonos.SonosGroupPlanner

class SonosGroupRowViewModel(c: AppContainer, private val id: String) : PlanViewModel(c) {
    fun planLeave() {
        val s = c.sonos.state.value.household?.speaker(id) ?: return
        pending = SonosGroupPlanner.leave(listOf(s))
    }
}

/** "Playing with Kitchen · Leave group" for a room that follows another room's group. */
@Composable
fun SonosGroupRow(id: String, onMessage: (String) -> Unit) {
    val vm = containerViewModel(key = "group-row-$id") { SonosGroupRowViewModel(it, id) }
    val ui by vm.household.collectAsState()
    val demo by vm.demo.collectAsState()
    LaunchedEffect(vm.message) { vm.message?.let { onMessage(it); vm.message = null } }
    val h = ui.household ?: return
    val leader = h.coordinatorOf(id)?.takeIf { it.uuid != id } ?: return

    Text("Playing with ${leader.roomName}'s group", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = vm::planLeave, enabled = !vm.busy) { Text("Leave group") }
    vm.pending?.let { ConfirmPlanDialog(it, onConfirm = vm::execute, onDismiss = vm::dismissPlan, demo = demo) }
}
