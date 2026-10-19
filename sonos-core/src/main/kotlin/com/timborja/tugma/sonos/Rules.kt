package com.timborja.tugma.sonos

enum class Severity { BLOCK, WARN, INFO }

data class Issue(val severity: Severity, val message: String)

val List<Issue>.blocked: Boolean get() = any { it.severity == Severity.BLOCK }
val List<Issue>.warningTexts: List<String> get() = filter { it.severity != Severity.BLOCK }.map { it.message }

private fun roleProblem(s: SonosSpeaker, household: HouseholdState): String? = when (val r = s.role) {
    BondRole.Standalone -> null
    BondRole.Bridge -> "${s.roomName} is a Bridge and can't play audio."
    is BondRole.StereoLeft, is BondRole.StereoRight -> "${s.roomName} is already in a stereo pair. Separate it first."
    is BondRole.HtPrimary -> "${s.roomName} is a home-theater soundbar with satellites."
    is BondRole.HtSatellite -> "${s.roomName} is already a satellite of ${household.nameOf(r.primary)}. Remove it there first."
    is BondRole.WithSub -> "${s.roomName} is bonded with a Sub. Remove the Sub first."
    is BondRole.BondedSub -> "${s.roomName} is the Sub for ${household.nameOf(r.primary)}."
}

object StereoPairRules {
    fun check(left: SonosSpeaker?, right: SonosSpeaker?, household: HouseholdState): List<Issue> = buildList {
        if (left == null || right == null) {
            add(Issue(Severity.BLOCK, "Pick a left and a right speaker."))
            return@buildList
        }
        if (left.uuid == right.uuid) {
            add(Issue(Severity.BLOCK, "Left and right must be different speakers."))
            return@buildList
        }
        for (s in listOf(left, right)) {
            roleProblem(s, household)?.let { add(Issue(Severity.BLOCK, it)) }
            if (s.isSub) add(Issue(Severity.BLOCK, "${s.roomName} is a Sub. Add it to a home theater as the sub instead."))
            if (s.isSoundbar) add(Issue(Severity.WARN, "${s.roomName} is a soundbar. Soundbars normally can't be stereo-paired and the speaker may refuse."))
            if (s.describeError != null) add(Issue(Severity.WARN, "Couldn't read ${s.roomName}'s model (${s.describeError})."))
        }
        if (left.modelName != null && right.modelName != null && left.modelName != right.modelName) {
            add(
                Issue(
                    Severity.INFO,
                    "Different models (${left.modelName} + ${right.modelName}). The official app blocks this; " +
                        "it usually works, but the two sides can sound different in level and tone.",
                ),
            )
        }
        if (left.softwareVersion != null && right.softwareVersion != null && left.softwareVersion != right.softwareVersion) {
            add(Issue(Severity.WARN, "Firmware differs (${left.softwareVersion} vs ${right.softwareVersion}). Update both first if pairing fails."))
        }
    }
}

enum class HtSlot(val code: String, val label: String, val experimental: Boolean = false) {
    LEFT_SURROUND("LR", "Left surround"),
    RIGHT_SURROUND("RR", "Right surround"),
    SUB("SW", "Sub"),
    FRONT_LEFT("LF", "Front left", experimental = true),
    FRONT_RIGHT("RF", "Front right", experimental = true),
    ;

    companion object {
        fun fromCode(code: String): HtSlot? = entries.firstOrNull { it.code == code }
    }
}

object HomeTheaterRules {
    /** Satellites the soundbar already has, by slot. */
    fun existing(bar: SonosSpeaker, household: HouseholdState): Map<HtSlot, SonosSpeaker> {
        val role = bar.role as? BondRole.HtPrimary ?: return emptyMap()
        return role.satellites.mapNotNull { a ->
            val slot = HtSlot.fromCode(a.channelSet.joinToString(",")) ?: return@mapNotNull null
            household.speaker(a.uuid)?.let { slot to it }
        }.toMap()
    }

    /** Speakers that may fill a slot on [bar]: standalone ones, plus the bar's current satellites. */
    fun candidates(bar: SonosSpeaker, household: HouseholdState): List<SonosSpeaker> =
        household.speakers.filter { s ->
            s.uuid != bar.uuid && (s.role.isStandalone || (s.role as? BondRole.HtSatellite)?.primary == bar.uuid)
        }

    /** The full ChannelMapSet: soundbar first, then every assigned slot. */
    fun channelMap(bar: SonosSpeaker, slots: Map<HtSlot, SonosSpeaker>): String =
        ChannelMap.format(
            listOf(ChannelAssignment(bar.uuid, listOf("LF", "RF"))) +
                HtSlot.entries.mapNotNull { slot -> slots[slot]?.let { ChannelAssignment(it.uuid, listOf(slot.code)) } },
        )

    fun check(
        bar: SonosSpeaker?,
        slots: Map<HtSlot, SonosSpeaker>,
        household: HouseholdState,
        allowExperimental: Boolean,
    ): List<Issue> = buildList {
        if (bar == null) {
            add(Issue(Severity.BLOCK, "Pick a soundbar."))
            return@buildList
        }
        if (!bar.isSoundbar) add(Issue(Severity.BLOCK, "${bar.roomName} isn't a soundbar or Amp, so it can't host a home theater."))
        if (!bar.role.isStandalone && bar.role !is BondRole.HtPrimary) roleProblem(bar, household)?.let { add(Issue(Severity.BLOCK, it)) }

        val existing = existing(bar, household)
        if (slots == existing) add(Issue(Severity.BLOCK, "Nothing to change. Assign at least one new speaker."))
        existing.forEach { (slot, s) ->
            if (slots[slot]?.uuid != s.uuid) {
                add(Issue(Severity.BLOCK, "${s.roomName} is already the ${slot.label.lowercase()}. Remove it from the soundbar first, then reassign."))
            }
        }

        val dupes = slots.values.groupBy { it.uuid }.filterValues { it.size > 1 }.values.map { it.first().roomName }
        dupes.forEach { add(Issue(Severity.BLOCK, "$it is assigned to more than one slot.")) }

        for ((slot, s) in slots) {
            if (s.uuid == bar.uuid) add(Issue(Severity.BLOCK, "The soundbar can't be its own ${slot.label.lowercase()}."))
            val isOwnSatellite = (s.role as? BondRole.HtSatellite)?.primary == bar.uuid
            if (!s.role.isStandalone && !isOwnSatellite) roleProblem(s, household)?.let { add(Issue(Severity.BLOCK, it)) }
            if (slot.experimental && !allowExperimental) {
                add(Issue(Severity.BLOCK, "${slot.label} is experimental. Turn on experimental fronts in Settings to use it."))
            }
            if (slot == HtSlot.SUB && !s.isSub) {
                add(Issue(Severity.WARN, "${s.roomName} isn't a Sub. The soundbar may refuse, or it may only play bass."))
            }
            if (slot != HtSlot.SUB && s.isSub) add(Issue(Severity.BLOCK, "${s.roomName} is a Sub; it can only be the sub."))
            if (s.isSoundbar) add(Issue(Severity.WARN, "${s.roomName} is a soundbar. Using it as a satellite is unusual and may be refused."))
        }

        val hasL = HtSlot.LEFT_SURROUND in slots
        val hasR = HtSlot.RIGHT_SURROUND in slots
        if (hasL != hasR) add(Issue(Severity.WARN, "Only one surround is set. Sonos normally expects both left and right."))
        if (slots.keys.any { it.experimental }) {
            add(Issue(Severity.WARN, "Front satellites aren't documented in any reference. The soundbar may refuse them or behave unexpectedly."))
        }
        if (slots.values.any { it.modelName != null && it.modelName != slots.values.first().modelName }) {
            add(Issue(Severity.INFO, "Mixed satellite models. The official app blocks this; levels may need adjusting afterwards."))
        }
    }

    fun summary(bar: SonosSpeaker, slots: Map<HtSlot, SonosSpeaker>): String =
        "${bar.roomName} keeps the front channels. " +
            slots.entries.sortedBy { it.key.ordinal }.joinToString("; ") { "${it.key.label}: ${it.value.displayName}" } + "."
}
