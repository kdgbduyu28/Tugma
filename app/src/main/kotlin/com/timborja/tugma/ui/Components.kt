package com.timborja.tugma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.sonos.Issue
import com.timborja.tugma.sonos.Severity
import com.timborja.tugma.sonos.SonosSpeaker

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun Badge(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** Dropdown picker. [label] is the slot name, [none] adds an empty choice. */
@Composable
fun SpeakerPicker(
    label: String,
    selected: SonosSpeaker?,
    options: List<SonosSpeaker>,
    onSelect: (SonosSpeaker?) -> Unit,
    modifier: Modifier = Modifier,
    none: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selected?.roomName ?: none ?: "Choose a speaker",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    selected?.let {
                        Text("${it.modelName ?: "Unknown model"} · ${it.endpoint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (enabled) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (none != null) DropdownMenuItem(text = { Text(none) }, onClick = { open = false; onSelect(null) })
            options.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.roomName)
                            Text("${s.modelName ?: "?"} · ${s.endpoint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onSelect(s) },
                )
            }
        }
    }
}

@Composable
fun IssueList(issues: List<Issue>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        issues.forEach { IssueRow(it) }
    }
}

@Composable
private fun IssueRow(issue: Issue) {
    val (icon, bg, fg) = when (issue.severity) {
        Severity.BLOCK -> Triple(Icons.Outlined.Block, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        Severity.WARN -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        Severity.INFO -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onSurface)
    }
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = issue.severity.name, tint = fg, modifier = Modifier.size(18.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = fg)
    }
}

/**
 * The only way a write reaches a speaker: the user reads the exact requests, ticks the
 * acknowledgement, and taps the confirm button, which calls [onConfirm] with the confirmed plan.
 */
@Composable
fun ConfirmPlanDialog(
    plan: CommandPlan,
    onConfirm: (com.timborja.tugma.core.ConfirmedPlan) -> Unit,
    onDismiss: () -> Unit,
    demo: Boolean,
) {
    var acknowledged by remember(plan) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(plan.title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                if (plan.warnings.isNotEmpty()) {
                    IssueList(plan.warnings.map { Issue(Severity.WARN, it) })
                }
                plan.requests.forEachIndexed { i, r ->
                    Text("Step ${i + 1}: ${r.label}", style = MaterialTheme.typography.labelLarge)
                    MonoBlock("${r.method} ${r.url}\n" + r.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + prettySoap(r.body))
                }
                Text(
                    if (demo) "Demo household: nothing real will change."
                    else "This changes your real speakers. It isn't supported by the speaker maker; a firmware update could break it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acknowledged = !acknowledged }) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I've read the requests above", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(plan.confirm()) }, enabled = acknowledged) { Text("Send to speaker") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Breaks a one-line SOAP body into readable lines without touching its content. */
fun prettySoap(body: String): String = body.replace("><", ">\n<")

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp).size(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        actions()
    }
}
