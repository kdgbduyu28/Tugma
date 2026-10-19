package com.timborja.tugma.sonos

import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.Capability
import com.timborja.tugma.core.SpeakerSummary
import com.timborja.tugma.core.VendorAdapter
import com.timborja.tugma.upnp.Discoverer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** One Sonos player: topology state merged with its device description. */
data class SonosSpeaker(
    val uuid: String,
    val roomName: String,
    val modelName: String?,
    val modelNumber: String?,
    val host: String,
    val port: Int,
    val role: BondRole,
    val roleLabel: String?,
    val groupId: String?,
    val isGroupCoordinator: Boolean,
    val invisible: Boolean,
    val isSoundbar: Boolean,
    val isSub: Boolean,
    val softwareVersion: String?,
    val describeError: String? = null,
) {
    val endpoint: Endpoint get() = Endpoint(host, port)

    /** "Living Room (Sonos Arc)" style label for pickers and dialogs. */
    val displayName: String get() = if (modelName.isNullOrBlank()) roomName else "$roomName ($modelName)"

    val capabilities: Set<Capability>
        get() = buildSet {
            if (!isSub && role.isStandalone) add(Capability.STEREO_PAIR)
            if (isSoundbar) add(Capability.HT_PRIMARY)
            add(Capability.HT_SATELLITE)
            add(Capability.GROUP)
            if (!invisible && role != BondRole.Bridge) {
                add(Capability.VOLUME)
                add(Capability.MUTE)
                add(Capability.TRANSPORT)
            }
        }

    fun toSummary(): SpeakerSummary = SpeakerSummary(
        id = uuid,
        brand = Brand.SONOS,
        name = roomName,
        model = modelName.orEmpty(),
        host = host,
        port = port,
        capabilities = capabilities,
        roleLabel = roleLabel,
        manufacturer = "Sonos",
        hidden = invisible,
    )
}

data class HouseholdState(
    val speakers: List<SonosSpeaker>,
    val topology: Topology,
    /** The speaker topology was read from. */
    val source: Endpoint,
    val fetchedAtMillis: Long,
) {
    fun speaker(uuid: String): SonosSpeaker? = speakers.firstOrNull { it.uuid == uuid }
    fun nameOf(uuid: String): String = speaker(uuid)?.roomName ?: topology.member(uuid)?.zoneName ?: uuid

    /** The speaker that leads [uuid]'s group (itself when standalone). */
    fun coordinatorOf(uuid: String): SonosSpeaker? =
        topology.groupOf(uuid)?.coordinator?.let(::speaker) ?: speaker(uuid)
}

class NoSpeakersFoundException : IOException("No speakers answered")

/** Discovers speakers and assembles a [HouseholdState]. */
class SonosHousehold(
    private val client: SonosClient,
    private val discoverer: Discoverer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun discover(): List<Endpoint> = discoverer.search()
        .filter { it.st == null || it.st.equals(SonosClient.ZONE_PLAYER_ST, ignoreCase = true) }
        .map { Endpoint(it.host, it.port.takeIf { p -> p > 0 } ?: SonosClient.DEFAULT_PORT) }
        .distinct()

    /** Reads topology from the first seed that answers, then describes every member in parallel. */
    suspend fun load(seeds: List<Endpoint>): HouseholdState {
        var lastError: Exception? = null
        for (seed in seeds.distinct()) {
            val topology = try {
                client.topology(seed)
            } catch (e: Exception) {
                lastError = e
                continue
            }
            return assemble(topology, seed)
        }
        throw lastError ?: NoSpeakersFoundException()
    }

    suspend fun refresh(previous: HouseholdState): HouseholdState =
        load(listOf(previous.source) + previous.speakers.filter { !it.invisible }.map { it.endpoint })

    private suspend fun assemble(initial: Topology, seed: Endpoint): HouseholdState {
        var topology = initial
        var source = seed
        // Satellites can serve stale topology (SoCo forwards those polls to the parent); do the same.
        val seedMember = topology.allMembers.firstOrNull { it.host == seed.host && it.port == seed.port }
        val parent = seedMember?.satelliteParent?.let(topology::member)
        if (seedMember?.isSatellite == true && parent?.host != null) {
            val parentEndpoint = Endpoint(parent.host, parent.port)
            runCatching { client.topology(parentEndpoint) }.getOrNull()?.let {
                topology = it
                source = parentEndpoint
            }
        }

        val members = topology.allMembers.filter { it.host != null }
        val infos = coroutineScope {
            members.map { m ->
                async {
                    m.uuid to withTimeoutOrNull(5_000) {
                        runCatching { client.describe(Endpoint(m.host!!, m.port)) }
                    }
                }
            }.awaitAll().toMap()
        }

        val coordinators = topology.groups.associate { it.id to it.coordinator }
        // Topology's ZoneName is the source of truth (a paired right speaker takes the left's room name).
        val names = members.associate { m ->
            val model = infos[m.uuid]?.getOrNull()?.modelName
            m.uuid to listOfNotNull(m.zoneName.ifBlank { null }, model).joinToString(" · ").ifBlank { m.uuid }
        }
        val speakers = members.map { m ->
            val info = infos[m.uuid]?.getOrNull()
            val error = infos[m.uuid]?.exceptionOrNull()?.message ?: if (infos[m.uuid] == null) "Timed out" else null
            val group = topology.groupOf(m.uuid)
            val role = BondRoles.roleOf(m, topology)
            SonosSpeaker(
                uuid = m.uuid,
                roomName = m.zoneName.ifBlank { info?.roomName.orEmpty() },
                modelName = info?.modelName,
                modelNumber = info?.modelNumber,
                host = m.host!!,
                port = m.port,
                role = role,
                roleLabel = BondRoles.label(role) { names[it] ?: it },
                groupId = group?.id,
                isGroupCoordinator = group != null && coordinators[group.id] == m.uuid,
                invisible = m.invisible,
                isSoundbar = info?.isSoundbar ?: false,
                isSub = info?.isSub ?: false,
                softwareVersion = info?.softwareVersion ?: m.softwareVersion,
                describeError = error,
            )
        }.sortedWith(compareBy({ it.roomName.lowercase() }, { it.invisible }))
        return HouseholdState(speakers, topology, source, clock())
    }
}

class SonosAdapter(private val household: SonosHousehold) : VendorAdapter {
    override val brand: Brand = Brand.SONOS

    override suspend fun discover(): List<SpeakerSummary> {
        val endpoints = household.discover()
        if (endpoints.isEmpty()) return emptyList()
        return household.load(endpoints).speakers.map { it.toSummary() }
    }
}
