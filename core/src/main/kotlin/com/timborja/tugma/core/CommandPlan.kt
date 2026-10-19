package com.timborja.tugma.core

import kotlinx.serialization.Serializable

/** One HTTP request that a write action will send, built before anything goes on the wire. */
@Serializable
data class PlannedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    /** Short label such as "AddBondedZones → 192.168.1.20". */
    val label: String,
)

/**
 * Everything a write action will do. Built by pure planning code so the UI can show the exact
 * requests in a confirmation dialog. It cannot be executed until it is [confirm]ed.
 */
@Serializable
data class CommandPlan(
    val title: String,
    val summary: String,
    val requests: List<PlannedRequest>,
    val warnings: List<String> = emptyList(),
) {
    /** Call only from a user confirmation (dialog or CLI prompt). */
    fun confirm(): ConfirmedPlan = ConfirmedPlan(this)
}

/** A [CommandPlan] the user has explicitly approved. Executors only accept this type. */
class ConfirmedPlan internal constructor(val plan: CommandPlan)
