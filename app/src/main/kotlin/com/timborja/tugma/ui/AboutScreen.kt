package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.timborja.tugma.AppContainer
import com.timborja.tugma.BuildConfig

class AboutViewModel(private val c: AppContainer) : ViewModel() {
    val demo = c.settings.demoMode
    val fronts = c.settings.experimentalFronts
    val manualHosts = c.settings.manualHosts

    fun setDemo(on: Boolean) = c.setDemoMode(on)

    fun setFronts(on: Boolean) = c.settings.setExperimentalFronts(on)
    fun resetDemo() = c.resetDemo()
    fun removeHost(h: String) = c.settings.removeManualHost(h)
}

@Composable
fun AboutScreen() {
    val vm = containerViewModel { AboutViewModel(it) }
    val demo by vm.demo.collectAsState()
    val fronts by vm.fronts.collectAsState()
    val hosts by vm.manualHosts.collectAsState()

    ScreenScaffold("About") { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tugma ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tugma (\"match\") pairs speakers the official apps won't: stereo pairs of different models, and any speaker as a soundbar's surrounds or sub.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "Unofficial. Tugma is not affiliated with, endorsed by or supported by Sonos, Inc. or any other speaker maker. " +
                        "It sends the same local-network commands the official apps use, in combinations those apps don't allow. " +
                        "A firmware update could stop it working at any time. Use at your own risk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            SectionLabel("Settings")
            SettingRow("Demo household", "Fake speakers to try every screen safely. Nothing real changes.", demo, vm::setDemo)
            if (demo) OutlinedButton(onClick = vm::resetDemo) { Text("Reset demo household") }
            SettingRow(
                "Experimental fronts",
                "Show front-left/right satellite slots. No reference documents these; the soundbar may refuse them.",
                fronts,
                vm::setFronts,
            )
            if (hosts.isNotEmpty()) {
                SectionLabel("Speakers added by IP")
                hosts.sorted().forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.removeHost(h) }) { Text("Forget") }
                    }
                }
            }

            SectionLabel("Licence and credits")
            Text(
                "Tugma is free software under the GNU GPL v3 or later.\n\n" +
                    "Protocol formats were checked against SoCo (github.com/SoCo/SoCo, MIT licence) and " +
                    "pair-sonos by Erik Månsson (github.com/erikmansson/pair-sonos, MIT licence). " +
                    "Test fixtures include sample topology payloads from SoCo's test suite.\n\n" +
                    "Soundcore support is ported from OpenSCQ30 by Kyle Scheuing and contributors " +
                    "(github.com/Oppzippy/OpenSCQ30, GPL-3.0), including its packet test vectors.\n\n" +
                    "Sonos is a trademark of Sonos, Inc., used here only to say which speakers Tugma works with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
