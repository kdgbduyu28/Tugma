package com.timborja.tugma.diag

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timborja.tugma.AppContainer
import com.timborja.tugma.ui.ScreenScaffold
import com.timborja.tugma.ui.containerViewModel
import kotlinx.coroutines.launch

class DiagnosticsViewModel(c: AppContainer, context: android.app.Application) : ViewModel() {
    private val runner = DiagnosticsRunner(context, c)
    var checks by mutableStateOf<List<Check>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set

    fun run() {
        if (running) return
        running = true
        checks = emptyList()
        viewModelScope.launch {
            runCatching { runner.run { checks = it } }
            running = false
        }
    }

    fun report(): String = runner.report(checks)
}

/** First stop when nothing shows up on real hardware: what was checked, what failed, and what to try. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel { DiagnosticsViewModel(it, context.applicationContext as android.app.Application) }

    ScreenScaffold("Diagnostics", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Checks the network, local discovery and each brand, then suggests what to try. Takes about 15 seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::run, enabled = !vm.running) { Text(if (vm.checks.isEmpty()) "Run checks" else "Run again") }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.report()), "Share report"))
                    },
                    enabled = vm.checks.isNotEmpty() && !vm.running,
                ) { Text("Share report") }
            }
            if (vm.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.checks.forEach { c ->
                val (bg, fg) = when (c.status) {
                    CheckStatus.PASS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    CheckStatus.WARN -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    CheckStatus.FAIL -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    CheckStatus.SKIP -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${c.status.mark} · ${c.name}", style = MaterialTheme.typography.titleSmall, color = fg)
                    Text(c.detail + if (c.millis > 0) " · ${c.millis} ms" else "", style = MaterialTheme.typography.bodyMedium, color = fg)
                    c.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg) }
                }
            }
            if (vm.checks.isNotEmpty() && !vm.running) {
                Text(
                    "The shared report lists failed request URLs (local addresses) but no request or response bodies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
