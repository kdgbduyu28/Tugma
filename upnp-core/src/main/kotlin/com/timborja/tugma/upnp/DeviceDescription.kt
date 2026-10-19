package com.timborja.tugma.upnp

import org.w3c.dom.Element
import java.net.URI

/** The parts of a UPnP `device_description.xml` Tugma cares about. Root-device fields are lifted to the top. */
data class DeviceDescription(
    val udn: String?,
    val friendlyName: String?,
    val manufacturer: String?,
    val modelName: String?,
    val modelNumber: String?,
    val deviceType: String?,
    /** Services of the root device and every embedded device. */
    val services: List<ServiceInfo>,
    /** Every leaf child element of the root `<device>` by name, for vendor extensions (e.g. Sonos `roomName`). */
    val extra: Map<String, String>,
    /** `<URLBase>` if the device declares one; control URLs resolve against it, else against LOCATION. */
    val urlBase: String? = null,
    /** Root device first, then embedded devices depth-first. */
    val devices: List<DeviceNode> = emptyList(),
) {
    data class ServiceInfo(val serviceType: String, val controlUrl: String, val scpdUrl: String)

    data class DeviceNode(
        val udn: String?,
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?,
        val modelNumber: String?,
        val deviceType: String?,
        val services: List<ServiceInfo>,
    ) {
        fun service(typePrefix: String): ServiceInfo? = services.firstOrNull { it.serviceType.startsWith(typePrefix) }
    }

    /** Absolute URL for a (usually relative) control/SCPD URL from this description. */
    fun resolve(url: String, location: String): String =
        runCatching { URI(urlBase?.takeIf { it.isNotBlank() } ?: location).resolve(url.trim()).toString() }.getOrDefault(url)

    companion object {
        fun parse(xml: String): DeviceDescription {
            val root = Xml.parse(xml)
            val device = root.child("device") ?: error("No <device> element in description")
            val nodes = mutableListOf<DeviceNode>()
            fun collect(dev: Element) {
                nodes += DeviceNode(
                    udn = dev.childText("UDN"),
                    friendlyName = dev.childText("friendlyName"),
                    manufacturer = dev.childText("manufacturer"),
                    modelName = dev.childText("modelName"),
                    modelNumber = dev.childText("modelNumber"),
                    deviceType = dev.childText("deviceType"),
                    services = dev.child("serviceList")?.children("service")?.map { s ->
                        ServiceInfo(
                            serviceType = s.childText("serviceType").orEmpty(),
                            controlUrl = s.childText("controlURL").orEmpty(),
                            scpdUrl = s.childText("SCPDURL").orEmpty(),
                        )
                    }.orEmpty(),
                )
                dev.child("deviceList")?.children("device")?.forEach(::collect)
            }
            collect(device)
            val extra = device.children()
                .filter { it.children().isEmpty() }
                .associate { it.name to it.textContent.trim() }
            val top = nodes.first()
            return DeviceDescription(
                udn = top.udn,
                friendlyName = top.friendlyName,
                manufacturer = top.manufacturer,
                modelName = top.modelName,
                modelNumber = top.modelNumber,
                deviceType = top.deviceType,
                services = nodes.flatMap { it.services },
                extra = extra,
                urlBase = root.childText("URLBase"),
                devices = nodes,
            )
        }
    }
}

/** Reads a service's SCPD document (e.g. `/xml/DeviceProperties1.xml`). */
object Scpd {
    fun actionNames(xml: String): Set<String> =
        Xml.parse(xml).child("actionList")?.children("action")
            ?.mapNotNull { it.childText("name") }
            ?.toSet()
            .orEmpty()

    /** `allowedValueRange` of a state variable, e.g. `Volume` → 0..100. */
    fun allowedRange(xml: String, variable: String): IntRange? {
        val v = Xml.parse(xml).child("serviceStateTable")?.children("stateVariable")
            ?.firstOrNull { it.childText("name") == variable } ?: return null
        val range = v.child("allowedValueRange") ?: return null
        val min = range.childText("minimum")?.toIntOrNull() ?: return null
        val max = range.childText("maximum")?.toIntOrNull() ?: return null
        return if (max > min) min..max else null
    }
}
