package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timborja.tugma.AppContainer
import com.timborja.tugma.upnp.LanAddress

/** Shown once, before the tabs: what Tugma is, and three ways to start. */
@Composable
fun WelcomeScreen(c: AppContainer) {
    var addingIp by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Tugma", style = MaterialTheme.typography.displaySmall)
            Text(
                "Pair and group speakers the official apps won't: stereo pairs of different Sonos models, any speaker as surrounds or a sub, " +
                    "plus volume, playback and EQ for Sonos, DLNA, Linkplay, Google Cast and some Bluetooth headphones.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "Unofficial and unsupported by any speaker maker. Anything that changes how speakers are set up shows you the exact " +
                        "requests first and waits for you to confirm. A snapshot is saved before each change so you can undo it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Button(onClick = { c.settings.setWelcomed() }, modifier = Modifier.fillMaxWidth()) { Text("Scan my network") }
            FilledTonalButton(onClick = { c.setDemoMode(true); c.settings.setWelcomed() }, modifier = Modifier.fillMaxWidth()) {
                Text("Try the demo household first")
            }
            OutlinedButton(onClick = { addingIp = !addingIp }, modifier = Modifier.fillMaxWidth()) { Text("Add a speaker by IP") }
            if (addingIp) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    singleLine = true,
                    label = { Text("IP address, e.g. 192.168.1.20") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val valid = ip.isNotEmpty() && LanAddress.isLocal(ip.substringBefore(':'))
                Button(onClick = { c.settings.addManualHost(ip); c.settings.setWelcomed() }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                    Text("Add and continue")
                }
                if (ip.isNotEmpty() && !valid) {
                    Text("That isn't a local network address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "Your phone needs to be on the same Wi-Fi as your speakers. If nothing shows up, About → Run diagnostics explains why.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
