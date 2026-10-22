package com.timborja.tugma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timborja.tugma.core.Brand
import com.timborja.tugma.remote.SleepTimers
import kotlinx.coroutines.delay

/** Sleep timer for brands without their own: the phone pauses the speaker when it's up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneSleepTimerSection(brand: Brand, id: String) {
    val context = LocalContext.current
    var left by remember(id) { mutableStateOf(SleepTimers.remainingMillis(context, brand, id)) }
    LaunchedEffect(id) {
        while (true) {
            left = SleepTimers.remainingMillis(context, brand, id)
            delay(15_000)
        }
    }

    SectionLabel("Sleep timer")
    val l = left
    Text(
        if (l == null) "Off" else "Pauses in about ${(l / 60_000) + 1} min",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 45, 60, 90).forEach { m ->
            AssistChip(
                onClick = { SleepTimers.set(context, brand, id, m); left = SleepTimers.remainingMillis(context, brand, id) },
                label = { Text(if (m < 60) "$m min" else "${m / 60}h${if (m % 60 > 0) " ${m % 60}" else ""}") },
            )
        }
        if (l != null) TextButton(onClick = { SleepTimers.cancel(context, brand, id); left = null }) { Text("Cancel timer") }
    }
    Text(
        "Your phone keeps this timer, so it may run a minute or two late while the phone sleeps.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
