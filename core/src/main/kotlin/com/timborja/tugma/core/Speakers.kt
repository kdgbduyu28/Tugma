package com.timborja.tugma.core

/** Speaker platforms Tugma can talk to. Each one is served by a [VendorAdapter]. */
enum class Brand(val displayName: String) {
    SONOS("Sonos"),
    UPNP("UPnP / DLNA"),
    CAST("Google Cast"),
    LINKPLAY("Linkplay"),
}

/** What a speaker can do. The UI only offers actions a speaker declares. */
enum class Capability {
    STEREO_PAIR,
    HT_PRIMARY,
    HT_SATELLITE,
    GROUP,
    CHANNEL_ASSIGN,
    VOLUME,
    MUTE,
    TRANSPORT,
}

/** Vendor-neutral view of one speaker, used by lists that mix brands. */
data class SpeakerSummary(
    val id: String,
    val brand: Brand,
    val name: String,
    val model: String,
    val host: String,
    val port: Int,
    val capabilities: Set<Capability>,
    /** Short human-readable role, e.g. "Stereo pair (left)". Null when standalone. */
    val roleLabel: String? = null,
    /** Maker as the device reports it (e.g. from a UPnP description). */
    val manufacturer: String? = null,
    /** Hidden members of a bond (e.g. the right half of a stereo pair). */
    val hidden: Boolean = false,
)

interface VendorAdapter {
    val brand: Brand
    suspend fun discover(): List<SpeakerSummary>
}
