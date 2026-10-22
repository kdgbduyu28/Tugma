package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.DeviceDescription

/** Model and room info from `/xml/device_description.xml`. */
data class SonosDeviceInfo(
    val uuid: String,
    val roomName: String?,
    val modelName: String?,
    val modelNumber: String?,
    val displayName: String?,
    val softwareVersion: String?,
    val serialNumber: String?,
    /** Declares an AudioIn service, i.e. has a line-in jack. */
    val hasLineIn: Boolean = false,
) {
    /** Same rule as SoCo: lowercase model name ends with a known soundbar/Amp product name. */
    val isSoundbar: Boolean
        get() = modelName?.lowercase()?.let { m -> SOUNDBARS.any { m.endsWith(it) } } ?: false

    val isSub: Boolean
        get() = listOfNotNull(modelName, displayName).any { SUB_PATTERN.containsMatchIn(it) }

    companion object {
        /** From SoCo `core.py` SOUNDBARS. "sonos amp" counts: it can be a home-theater primary. */
        val SOUNDBARS = listOf("arc", "arc sl", "arc ultra", "beam", "beam ultra", "playbase", "playbar", "ray", "sonos amp")
        private val SUB_PATTERN = Regex("""\bsub\b""", RegexOption.IGNORE_CASE)

        fun from(description: DeviceDescription): SonosDeviceInfo = SonosDeviceInfo(
            uuid = description.udn.orEmpty().removePrefix("uuid:"),
            roomName = description.extra["roomName"],
            modelName = description.modelName,
            modelNumber = description.modelNumber,
            displayName = description.extra["displayName"],
            softwareVersion = description.extra["softwareVersion"],
            serialNumber = description.extra["serialNum"],
            hasLineIn = (description.services + description.devices.flatMap { it.services })
                .any { it.serviceType.startsWith("urn:schemas-upnp-org:service:AudioIn:") },
        )
    }
}
