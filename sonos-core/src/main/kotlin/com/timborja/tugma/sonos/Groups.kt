package com.timborja.tugma.sonos

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient

/** A multiroom group as the user sees it: one coordinator plus the other visible rooms playing along. */
data class RoomGroup(val id: String, val coordinator: SonosSpeaker, val members: List<SonosSpeaker>) {
    /** Coordinator first. */
    val all: List<SonosSpeaker> get() = listOf(coordinator) + members
    val name: String get() = if (members.isEmpty()) coordinator.roomName else "${coordinator.roomName} + ${members.size}"
}

/** Rooms that can play, grouped the way ZoneGroupState reports them. Bonded halves and satellites are hidden. */
fun HouseholdState.roomGroups(): List<RoomGroup> =
    topology.groups.mapNotNull { g ->
        val rooms = g.members.mapNotNull { speaker(it.uuid) }.filter(SonosGroupRules::canGroup)
        val coordinator = speaker(g.coordinator)?.takeIf(SonosGroupRules::canGroup) ?: rooms.firstOrNull() ?: return@mapNotNull null
        RoomGroup(g.id, coordinator, rooms.filter { it.uuid != coordinator.uuid })
    }.sortedBy { it.coordinator.roomName.lowercase() }

object SonosGroupRules {
    /** Visible, audio-capable rooms. Stereo-pair right halves, satellites and Bridges can't be grouped on their own. */
    fun canGroup(s: SonosSpeaker): Boolean =
        !s.invisible && s.role != BondRole.Bridge && s.role !is BondRole.StereoRight &&
            s.role !is BondRole.HtSatellite && s.role !is BondRole.BondedSub && !(s.isSub && s.role.isStandalone)

    fun check(coordinator: SonosSpeaker?, members: List<SonosSpeaker>, household: HouseholdState): List<Issue> = buildList {
        if (coordinator == null) {
            add(Issue(Severity.BLOCK, "Pick the room to group with."))
            return@buildList
        }
        if (members.isEmpty()) add(Issue(Severity.BLOCK, "Pick at least one room to add."))
        for (s in listOf(coordinator) + members) {
            if (canGroup(s)) continue
            val why = when (val r = s.role) {
                is BondRole.StereoRight -> "That's the right speaker of ${household.nameOf(r.partner)}'s stereo pair. Group the room (its left speaker) instead."
                is BondRole.HtSatellite -> "That's a satellite of ${household.nameOf(r.primary)}. Group the soundbar's room instead."
                is BondRole.BondedSub -> "That's the Sub for ${household.nameOf(r.primary)}. Group that room instead."
                BondRole.Bridge -> "${s.roomName} is a Bridge and can't play audio."
                else -> "${s.roomName} can't be grouped on its own."
            }
            add(Issue(Severity.BLOCK, why))
        }
        if (members.any { it.uuid == coordinator.uuid }) add(Issue(Severity.BLOCK, "${coordinator.roomName} can't join itself."))
        val already = household.topology.groupOf(coordinator.uuid)
        members.filter { m -> already?.members?.any { it.uuid == m.uuid } == true }.forEach {
            add(Issue(Severity.INFO, "${it.roomName} is already in this group; it'll be left as is."))
        }
        members.filter { it.isGroupCoordinator && household.roomGroups().any { g -> g.coordinator.uuid == it.uuid && g.members.isNotEmpty() } }.forEach {
            add(Issue(Severity.WARN, "${it.roomName} leads its own group. Only ${it.roomName} moves; the rest of that group keeps playing together."))
        }
        if (coordinator.role is BondRole.HtPrimary || coordinator.isSoundbar) {
            add(Issue(Severity.INFO, "Grouping with a soundbar adds a little delay to TV audio sync on the other rooms."))
        }
    }
}

/**
 * Multiroom writes. Joining points the member's AVTransport at `x-rincon:<coordinator>`; leaving
 * makes it a standalone group again. Same calls the Sonos app and SoCo `join()` / `unjoin()` use.
 */
object SonosGroupPlanner {
    const val JOIN = "SetAVTransportURI"
    const val LEAVE = "BecomeCoordinatorOfStandaloneGroup"

    fun join(coordinator: SonosSpeaker, members: List<SonosSpeaker>, household: HouseholdState, warnings: List<String> = emptyList()): CommandPlan {
        val current = household.topology.groupOf(coordinator.uuid)?.members?.map { it.uuid }.orEmpty().toSet()
        val moving = members.filter { it.uuid !in current && it.uuid != coordinator.uuid }
        return CommandPlan(
            title = "Group ${moving.joinToString(", ") { it.roomName }} with ${coordinator.roomName}",
            summary = "${moving.joinToString(", ") { it.roomName }} will play whatever ${coordinator.roomName} plays. " +
                "Stop anything they were playing first if you want to keep it.",
            requests = moving.map { m ->
                avt(m, JOIN, listOf("InstanceID" to "0", "CurrentURI" to "x-rincon:${coordinator.uuid}", "CurrentURIMetaData" to ""), "Join ${m.roomName} → ${coordinator.roomName}")
            },
            warnings = warnings,
        )
    }

    fun leave(members: List<SonosSpeaker>): CommandPlan = CommandPlan(
        title = if (members.size == 1) "Remove ${members.single().roomName} from its group" else "Ungroup ${members.size} rooms",
        summary = "${members.joinToString(", ") { it.roomName }} stop playing along and become standalone rooms. Nothing else changes.",
        requests = members.map { avt(it, LEAVE, listOf("InstanceID" to "0"), "$LEAVE → ${it.roomName}") },
    )

    /** Every member except the coordinator leaves. */
    fun ungroup(group: RoomGroup): CommandPlan = leave(group.members).copy(title = "Ungroup ${group.coordinator.roomName}")

    /** Everything into one group led by [coordinator] ("party mode"). */
    fun everything(coordinator: SonosSpeaker, household: HouseholdState): CommandPlan =
        join(coordinator, household.roomGroups().flatMap { it.all }.filter { it.uuid != coordinator.uuid }, household)
            .copy(title = "Group every room with ${coordinator.roomName}")

    private fun avt(s: SonosSpeaker, action: String, args: List<Pair<String, String>>, label: String) =
        SoapCall(s.endpoint.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, args).toPlannedRequest(label)
}

/**
 * Pauses every group that's playing (Pause goes to each coordinator). Returns how many groups
 * were paused. Groups that are already stopped are skipped, so their errors don't count.
 */
suspend fun SonosClient.pauseAll(household: HouseholdState): Int = pauseAllGroups(household).size

/** Like [pauseAll], but returns the coordinators it paused so [resume] can restart exactly those. */
suspend fun SonosClient.pauseAllGroups(household: HouseholdState): List<SonosSpeaker> =
    household.roomGroups().map { it.coordinator }.filter { c ->
        val control = playback(c.endpoint, c.endpoint)
        val playing = runCatching { control.status().transport == com.timborja.tugma.core.TransportState.PLAYING }.getOrDefault(false)
        playing && runCatching { control.pause() }.isSuccess
    }

/** Plays the given coordinators again; returns how many accepted. */
suspend fun SonosClient.resume(coordinators: List<SonosSpeaker>): Int =
    coordinators.count { c -> runCatching { playback(c.endpoint, c.endpoint).play() }.isSuccess }

/** Whole-group volume, sent to the group's coordinator (GroupRenderingControl). */
class SonosGroupVolume(private val soap: SoapClient, private val coordinator: Endpoint) {
    private fun call(action: String, vararg args: Pair<String, String>) =
        SoapCall(coordinator.baseUrl, GRC_CONTROL, GRC_SERVICE, action, listOf("InstanceID" to "0") + args)

    suspend fun volume(): Int? = soap.call(call("GetGroupVolume"))["CurrentVolume"]?.trim()?.toIntOrNull()
    suspend fun muted(): Boolean? = soap.call(call("GetGroupMute"))["CurrentMute"]?.trim()?.let { it == "1" }

    /** Sonos scales each member relative to its current level, so a snapshot is taken first. */
    suspend fun setVolume(value: Int) {
        soap.call(call("SnapshotGroupVolume"))
        soap.call(call("SetGroupVolume", "DesiredVolume" to value.coerceIn(0, 100).toString()))
    }

    suspend fun setMuted(muted: Boolean) {
        soap.call(call("SetGroupMute", "DesiredMute" to if (muted) "1" else "0"))
    }

    companion object {
        const val GRC_SERVICE = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
        const val GRC_CONTROL = "/MediaRenderer/GroupRenderingControl/Control"
    }
}
