package com.timborja.tugma.sonos

import com.timborja.tugma.core.CommandPlan
import com.timborja.tugma.core.PlannedRequest
import com.timborja.tugma.upnp.SoapCall
import kotlinx.serialization.Serializable

/**
 * A saved setup: which rooms play together, their volumes, and optionally a favorite to start.
 * Rooms are stored by UUID, so renaming a room doesn't break the scene.
 */
@Serializable
data class SonosScene(
    val id: String,
    val name: String,
    val leaderUuid: String,
    val memberUuids: List<String> = emptyList(),
    /** Room UUID → volume 0–100; rooms left out keep their volume. */
    val volumes: Map<String, Int> = emptyMap(),
    val favorite: SceneFavorite? = null,
    /** Optional reminder: a notification at this time offers to apply the scene. */
    val reminder: SceneReminder? = null,
) {
    val rooms: List<String> get() = listOf(leaderUuid) + memberUuids.filter { it != leaderUuid }
}

/** A daily-or-weekly time. Days use Sonos' numbering, 0 = Sunday … 6 = Saturday. */
@Serializable
data class SceneReminder(val minuteOfDay: Int, val days: Set<Int> = (0..6).toSet()) {
    val time: String get() = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    val label: String get() = "$time · ${Recurrence.label(Recurrence.of(days))}"

    /** The next occurrence strictly after [now], or null when no day is selected. */
    fun next(now: java.time.ZonedDateTime): java.time.ZonedDateTime? {
        if (days.isEmpty() || minuteOfDay !in 0 until 24 * 60) return null
        var day = now.toLocalDate()
        repeat(8) {
            val sonosDay = day.dayOfWeek.value % 7 // Monday=1 … Sunday=7 → Sunday=0
            val at = day.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(now.zone)
            if (sonosDay in days && at.isAfter(now)) return at
            day = day.plusDays(1)
        }
        return null
    }
}

@Serializable
data class SceneFavorite(val title: String, val uri: String, val metadata: String, val needsQueue: Boolean) {
    companion object {
        fun of(f: SonosFavorite) = SceneFavorite(f.title, f.uri, f.metadata, f.needsQueue)
    }
}

/**
 * Turns a scene into one plan: leaves, joins, volumes, then the favorite on the leader. Every
 * step is an ordinary UPnP request, so it's shown and confirmed like any other plan.
 */
object ScenePlanner {
    /** Why the scene can't run right now, or null when it can. */
    fun problem(scene: SonosScene, household: HouseholdState): String? =
        if (household.speaker(scene.leaderUuid)?.let(SonosGroupRules::canGroup) == true) null
        else "${household.nameOf(scene.leaderUuid)} (the scene's main room) is offline or bonded into another room."

    /** Null when the scene can't run (see [problem]) or nothing would change. */
    fun plan(scene: SonosScene, household: HouseholdState): CommandPlan? {
        val warnings = mutableListOf<String>()
        val leader = household.speaker(scene.leaderUuid)?.takeIf(SonosGroupRules::canGroup) ?: return null
        val members = scene.memberUuids.filter { it != leader.uuid }.mapNotNull { uuid ->
            household.speaker(uuid)?.takeIf(SonosGroupRules::canGroup)
                ?: null.also { warnings += "${household.nameOf(uuid)} is offline or bonded now; it's skipped." }
        }
        val requests = mutableListOf<PlannedRequest>()

        // Rooms currently with the leader that the scene doesn't include, and the leader itself if it follows someone else.
        val leaderGroup = household.topology.groupOf(leader.uuid)
        val strays = leaderGroup?.members.orEmpty().mapNotNull { household.speaker(it.uuid) }
            .filter { SonosGroupRules.canGroup(it) && it.uuid != leader.uuid && it.uuid !in members.map(SonosSpeaker::uuid) }
        val leaderFollows = leaderGroup != null && leaderGroup.coordinator != leader.uuid
        val leaves = if (leaderFollows) listOf(leader) else strays
        if (leaves.isNotEmpty()) requests += SonosGroupPlanner.leave(leaves).requests

        // After a leave the leader stands alone, so every member needs joining.
        val joinPlan = if (leaderFollows) {
            SonosGroupPlanner.join(leader, members, household.copy(topology = household.topology.copy(groups = household.topology.groups.filter { it.id != leaderGroup?.id })))
        } else {
            SonosGroupPlanner.join(leader, members, household)
        }
        requests += joinPlan.requests

        for ((uuid, volume) in scene.volumes) {
            val s = household.speaker(uuid)?.takeIf { !it.invisible } ?: continue
            requests += SoapCall(
                s.endpoint.baseUrl, SonosClient.RC_CONTROL, SonosClient.RC_SERVICE, "SetVolume",
                listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()),
            ).toPlannedRequest("Volume ${volume.coerceIn(0, 100)} → ${s.roomName}")
        }

        scene.favorite?.let { f -> requests += favoriteRequests(leader, f) }

        if (requests.isEmpty()) return null
        val names = (listOf(leader) + members).joinToString(", ") { it.roomName }
        return CommandPlan(
            title = "Scene: ${scene.name}",
            summary = "$names play together" + (scene.favorite?.let { ", starting ${it.title}" } ?: "") + ".",
            requests = requests,
            warnings = warnings + if (scene.favorite?.needsQueue == true) listOf("${scene.favorite.title} replaces ${leader.roomName}'s queue.") else emptyList(),
        )
    }

    private fun favoriteRequests(leader: SonosSpeaker, f: SceneFavorite): List<PlannedRequest> {
        fun avt(action: String, label: String, vararg args: Pair<String, String>) =
            SoapCall(leader.endpoint.baseUrl, SonosClient.AVT_CONTROL, SonosClient.AVT_SERVICE, action, listOf("InstanceID" to "0") + args).toPlannedRequest(label)
        val play = avt("Play", "Play → ${leader.roomName}", "Speed" to "1")
        return if (f.needsQueue) {
            listOf(
                avt("RemoveAllTracksFromQueue", "Clear queue → ${leader.roomName}"),
                avt("AddURIToQueue", "Queue ${f.title}", "EnqueuedURI" to f.uri, "EnqueuedURIMetaData" to f.metadata, "DesiredFirstTrackNumberEnqueued" to "0", "EnqueueAsNext" to "0"),
                avt("SetAVTransportURI", "Play from queue → ${leader.roomName}", "CurrentURI" to "x-rincon-queue:${leader.uuid}#0", "CurrentURIMetaData" to ""),
                play,
            )
        } else {
            listOf(avt("SetAVTransportURI", "Start ${f.title}", "CurrentURI" to f.uri, "CurrentURIMetaData" to f.metadata), play)
        }
    }
}
