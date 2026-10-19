package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.attr
import com.timborja.tugma.upnp.child
import com.timborja.tugma.upnp.children
import java.net.URI

/**
 * One `<ZoneGroupMember>` or `<Satellite>` from ZoneGroupState.
 * Satellites (home-theater surrounds/sub) are nested under their soundbar in [satellites].
 */
data class ZoneMember(
    val uuid: String,
    val zoneName: String,
    val location: String?,
    val host: String?,
    val port: Int,
    val invisible: Boolean,
    val isZoneBridge: Boolean,
    val channelMapSet: String?,
    val htSatChanMapSet: String?,
    val softwareVersion: String?,
    val isSatellite: Boolean,
    val satelliteParent: String?,
    val satellites: List<ZoneMember>,
)

data class ZoneGroup(val id: String, val coordinator: String, val members: List<ZoneMember>)

data class Topology(val groups: List<ZoneGroup>, val rawXml: String) {
    /** Every member including nested satellites. */
    val allMembers: List<ZoneMember> by lazy {
        groups.flatMap { g -> g.members.flatMap { listOf(it) + it.satellites } }
    }

    private val byUuid by lazy { allMembers.associateBy { it.uuid } }

    fun member(uuid: String): ZoneMember? = byUuid[uuid]

    fun groupOf(uuid: String): ZoneGroup? =
        groups.firstOrNull { g -> g.members.any { it.uuid == uuid || it.satellites.any { s -> s.uuid == uuid } } }

    fun roleOf(uuid: String): BondRole = member(uuid)?.let { BondRoles.roleOf(it, this) } ?: BondRole.Standalone

    companion object {
        /** Parses the XML string carried in GetZoneGroupState's `ZoneGroupState` output argument. */
        fun parse(xml: String): Topology {
            val root = Xml.parse(xml)
            // Pre-10.1 firmware puts <ZoneGroup> directly under the root (same fallback as SoCo).
            val container = root.child("ZoneGroups") ?: root
            val groups = container.children("ZoneGroup").map { g ->
                ZoneGroup(
                    id = g.attr("ID").orEmpty(),
                    coordinator = g.attr("Coordinator").orEmpty(),
                    members = g.children("ZoneGroupMember").map { parseMember(it, isSatellite = false, parent = null) },
                )
            }
            return Topology(groups, xml)
        }

        private fun parseMember(e: org.w3c.dom.Element, isSatellite: Boolean, parent: String?): ZoneMember {
            val uuid = e.attr("UUID").orEmpty()
            val location = e.attr("Location")
            val uri = location?.let { runCatching { URI(it) }.getOrNull() }
            return ZoneMember(
                uuid = uuid,
                zoneName = e.attr("ZoneName").orEmpty(),
                location = location,
                host = uri?.host,
                port = uri?.port?.takeIf { it > 0 } ?: SonosClient.DEFAULT_PORT,
                invisible = e.attr("Invisible") == "1",
                isZoneBridge = e.attr("IsZoneBridge") == "1",
                channelMapSet = e.attr("ChannelMapSet")?.takeIf { it.isNotBlank() },
                htSatChanMapSet = e.attr("HTSatChanMapSet")?.takeIf { it.isNotBlank() },
                softwareVersion = e.attr("SoftwareVersion"),
                isSatellite = isSatellite,
                satelliteParent = parent,
                satellites = e.children("Satellite").map { parseMember(it, isSatellite = true, parent = uuid) },
            )
        }
    }
}

data class ChannelAssignment(val uuid: String, val channels: List<String>) {
    /** Channels with repeats collapsed, e.g. "LF,LF" → {LF}. */
    val channelSet: Set<String> get() = channels.toSet()
}

/** Parses and formats `ChannelMapSet` / `HTSatChanMapSet` strings: `UUID:CH[,CH];UUID:CH…`. */
object ChannelMap {
    fun parse(value: String?): List<ChannelAssignment> =
        value.orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) return@mapNotNull null
            ChannelAssignment(part.substring(0, i).trim(), part.substring(i + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }

    fun format(assignments: List<ChannelAssignment>): String =
        assignments.joinToString(";") { "${it.uuid}:${it.channels.joinToString(",")}" }

    fun stereoPair(leftUuid: String, rightUuid: String): String =
        format(listOf(ChannelAssignment(leftUuid, listOf("LF", "LF")), ChannelAssignment(rightUuid, listOf("RF", "RF"))))
}
