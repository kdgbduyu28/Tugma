package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.SoapFault

/** Per-speaker settings. Null means the speaker didn't report it. */
data class SpeakerSettings(
    val statusLight: Boolean?,
    /** True when the touch controls are locked (disabled). */
    val buttonsLocked: Boolean?,
    val trueplayEnabled: Boolean?,
    /** False when the room was never tuned, so there's nothing to switch on. */
    val trueplayAvailable: Boolean?,
    val roomName: String?,
)

/**
 * Status light, touch-control lock, Trueplay and room name, as in SoCo `status_light`,
 * `buttons_enabled`, `trueplay` and `player_name`. Send them to the player itself.
 */
class SonosSettings(private val soap: SoapClient, private val speaker: Endpoint) {
    private fun dp(action: String, vararg args: Pair<String, String>) =
        SoapCall(speaker.baseUrl, SonosClient.DP_CONTROL, SonosClient.DP_SERVICE, action, args.toList())

    private fun rc(action: String, vararg args: Pair<String, String>) =
        SoapCall(speaker.baseUrl, SonosClient.RC_CONTROL, SonosClient.RC_SERVICE, action, listOf("InstanceID" to "0") + args)

    private suspend fun read(call: SoapCall): Map<String, String>? = try { soap.call(call) } catch (_: SoapFault) { null }

    suspend fun read(): SpeakerSettings {
        val led = read(dp("GetLEDState"))?.get("CurrentLEDState")
        val lock = read(dp("GetButtonLockState"))?.get("CurrentButtonLockState")
        val tp = read(rc("GetRoomCalibrationStatus"))
        val zone = read(dp("GetZoneAttributes"))
        return SpeakerSettings(
            statusLight = led?.let { it.equals("On", ignoreCase = true) },
            buttonsLocked = lock?.let { it.equals("On", ignoreCase = true) },
            trueplayEnabled = tp?.get("RoomCalibrationEnabled")?.let { it == "1" },
            trueplayAvailable = tp?.get("RoomCalibrationAvailable")?.let { it == "1" },
            roomName = zone?.get("CurrentZoneName"),
        )
    }

    suspend fun setStatusLight(on: Boolean) {
        soap.call(dp("SetLEDState", "DesiredLEDState" to if (on) "On" else "Off"))
    }

    suspend fun setButtonsLocked(locked: Boolean) {
        soap.call(dp("SetButtonLockState", "DesiredButtonLockState" to if (locked) "On" else "Off"))
    }

    suspend fun setTrueplay(enabled: Boolean) {
        soap.call(rc("SetRoomCalibrationStatus", "RoomCalibrationEnabled" to if (enabled) "1" else "0"))
    }

    /** Renames the room, keeping its icon and configuration as the speaker reports them. */
    suspend fun rename(name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_NAME) { "Room names are 1–$MAX_NAME characters" }
        val current = read(dp("GetZoneAttributes"))
        soap.call(
            dp(
                "SetZoneAttributes",
                "DesiredZoneName" to clean,
                "DesiredIcon" to current?.get("CurrentIcon").orEmpty(),
                "DesiredConfiguration" to current?.get("CurrentConfiguration").orEmpty(),
            ),
        )
    }

    companion object {
        const val MAX_NAME = 32
    }
}
