package com.timborja.tugma.sonos

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.PlannedRequest
import kotlinx.serialization.Serializable

/** Topology saved right before a write, so the user can see (and undo) what changed. */
@Serializable
data class TopologySnapshot(
    val id: String,
    val createdAtMillis: Long,
    /** Title of the plan that was about to run. */
    val reason: String,
    val rawZoneGroupState: String,
    val speakers: List<Line>,
) {
    @Serializable
    data class Line(val uuid: String, val room: String, val model: String?, val role: String?)

    companion object {
        fun of(id: String, reason: String, state: HouseholdState, now: Long): TopologySnapshot = TopologySnapshot(
            id = id,
            createdAtMillis = now,
            reason = reason,
            rawZoneGroupState = state.topology.rawXml,
            speakers = state.speakers.map { Line(it.uuid, it.roomName, it.modelName, it.roleLabel) },
        )
    }
}

data class RoleChange(val uuid: String, val room: String, val before: String?, val after: String?)

/** Compares a snapshot with the current household and suggests the commands that would restore it. */
object RestorePlanner {
    fun diff(snapshot: TopologySnapshot, current: HouseholdState): List<RoleChange> {
        val now = current.speakers.associateBy { it.uuid }
        val then = snapshot.speakers.associateBy { it.uuid }
        return (then.keys + now.keys).mapNotNull { uuid ->
            val before = then[uuid]?.role
            val after = now[uuid]?.roleLabel
            if (before == after) null
            else RoleChange(uuid, now[uuid]?.roomName ?: then[uuid]?.room ?: uuid, before, after)
        }
    }

    /** Returns null when nothing differs. Removals run before additions. */
    fun plan(snapshot: TopologySnapshot, current: HouseholdState): CommandPlan? {
        val old = Topology.parse(snapshot.rawZoneGroupState)
        val requests = mutableListOf<PlannedRequest>()
        val warnings = mutableListOf<String>()
        fun oldRole(uuid: String) = old.roleOf(uuid)

        // 1. Undo pairs and satellites that didn't exist in the snapshot.
        for (s in current.speakers) {
            val role = s.role
            if (role is BondRole.StereoLeft) {
                val was = oldRole(s.uuid) as? BondRole.StereoLeft
                if (was == null || was.partner != role.partner) {
                    requests += SonosPlanner.separateStereoPair(s, current.topology.member(s.uuid)?.channelMapSet, legacy = false).requests
                }
            }
            if (role is BondRole.HtSatellite) {
                val was = oldRole(s.uuid) as? BondRole.HtSatellite
                if (was == null || was.primary != role.primary || was.channel != role.channel) {
                    val bar = current.speaker(role.primary) ?: continue
                    requests += SonosPlanner.removeHtSatellite(bar, s.uuid, s.roomName).requests
                }
            }
        }

        // 2. Re-create pairs and home theaters that the snapshot had.
        for (m in old.allMembers) {
            when (val was = old.roleOf(m.uuid)) {
                is BondRole.StereoLeft -> {
                    val nowRole = current.speaker(m.uuid)?.role
                    if (nowRole !is BondRole.StereoLeft || nowRole.partner != was.partner) {
                        val left = current.speaker(m.uuid)
                        val right = current.speaker(was.partner)
                        if (left == null || right == null) {
                            warnings += "Can't restore the pair of ${m.zoneName}: one speaker is offline."
                        } else {
                            requests += SonosPlanner.stereoPair(left, right, legacy = false).requests
                        }
                    }
                }
                is BondRole.HtPrimary -> {
                    val bar = current.speaker(m.uuid)
                    val nowSats = (bar?.role as? BondRole.HtPrimary)?.satellites.orEmpty().toSet()
                    if (bar != null && was.satellites.toSet() != nowSats && m.htSatChanMapSet != null) {
                        val missing = was.satellites.filter { it !in nowSats }
                        if (missing.isNotEmpty()) {
                            requests += SonosPlanner.addHtSatellites(bar, m.htSatChanMapSet, "", emptyList()).requests
                        }
                    }
                }
                else -> Unit
            }
        }

        if (requests.isEmpty()) return null
        return CommandPlan(
            title = "Restore snapshot: ${snapshot.reason}",
            summary = "Puts bonds back the way they were before \"${snapshot.reason}\".",
            requests = requests.distinctBy { it.url + it.body },
            warnings = warnings + "Restores are best-effort. Review each step; Trueplay tuning may need redoing.",
        )
    }
}
