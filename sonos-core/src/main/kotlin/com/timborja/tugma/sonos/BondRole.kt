package com.timborja.tugma.sonos

/** How a speaker is currently bonded, derived from ZoneGroupState. */
sealed interface BondRole {
    data object Standalone : BondRole
    data object Bridge : BondRole
    data class StereoLeft(val partner: String, val sub: String? = null) : BondRole
    data class StereoRight(val partner: String) : BondRole
    /** Soundbar/Amp with satellites. [satellites] excludes the primary's own `LF,RF` entry. */
    data class HtPrimary(val satellites: List<ChannelAssignment>) : BondRole
    data class HtSatellite(val primary: String, val channel: String) : BondRole
    /** A non-HT speaker bonded with a Sub through ChannelMapSet (e.g. Five + Sub). */
    data class WithSub(val sub: String) : BondRole
    data class BondedSub(val primary: String) : BondRole

    val isStandalone: Boolean get() = this == Standalone
}

object BondRoles {
    fun roleOf(m: ZoneMember, topology: Topology): BondRole {
        if (m.isZoneBridge) return BondRole.Bridge

        val parent = m.satelliteParent?.let(topology::member)
        val htMap = ChannelMap.parse(m.htSatChanMapSet ?: parent?.htSatChanMapSet)
        if (htMap.isNotEmpty()) {
            val primary = htMap.firstOrNull { it.channelSet == setOf("LF", "RF") }?.uuid ?: parent?.uuid
            val mine = htMap.firstOrNull { it.uuid == m.uuid }
            if (primary == m.uuid) {
                return BondRole.HtPrimary(htMap.filter { it.uuid != m.uuid })
            }
            if (mine != null && primary != null) {
                return BondRole.HtSatellite(primary, mine.channelSet.joinToString(","))
            }
        }
        if (m.isSatellite && parent != null) return BondRole.HtSatellite(parent.uuid, "?")

        val map = ChannelMap.parse(m.channelMapSet)
        val mine = map.firstOrNull { it.uuid == m.uuid } ?: return BondRole.Standalone
        val sub = map.firstOrNull { it.channelSet == setOf("SW") }?.uuid
        return when (mine.channelSet) {
            setOf("LF") -> map.firstOrNull { it.channelSet == setOf("RF") }
                ?.let { BondRole.StereoLeft(it.uuid, sub) } ?: BondRole.Standalone
            setOf("RF") -> map.firstOrNull { it.channelSet == setOf("LF") }
                ?.let { BondRole.StereoRight(it.uuid) } ?: BondRole.Standalone
            setOf("SW") -> map.firstOrNull { it.uuid != m.uuid && "SW" !in it.channelSet }
                ?.let { BondRole.BondedSub(it.uuid) } ?: BondRole.Standalone
            setOf("LF", "RF") -> if (sub != null) BondRole.WithSub(sub) else BondRole.Standalone
            else -> BondRole.Standalone
        }
    }

    fun channelLabel(code: String): String = when (code) {
        "LF" -> "front left"
        "RF" -> "front right"
        "LR" -> "left surround"
        "RR" -> "right surround"
        "SW" -> "sub"
        "LF,RF" -> "front"
        else -> code
    }

    fun label(role: BondRole, nameOf: (String) -> String): String? = when (role) {
        BondRole.Standalone -> null
        BondRole.Bridge -> "Bridge"
        is BondRole.StereoLeft -> "Stereo pair · left · right is ${nameOf(role.partner)}"
        is BondRole.StereoRight -> "Stereo pair · right · left is ${nameOf(role.partner)}"
        is BondRole.HtPrimary -> "Home theater · " + role.satellites.joinToString(", ") {
            "${channelLabel(it.channelSet.joinToString(","))}: ${nameOf(it.uuid)}"
        }
        is BondRole.HtSatellite -> "Home theater ${channelLabel(role.channel)} for ${nameOf(role.primary)}"
        is BondRole.WithSub -> "Bonded with sub ${nameOf(role.sub)}"
        is BondRole.BondedSub -> "Sub for ${nameOf(role.primary)}"
    }
}
