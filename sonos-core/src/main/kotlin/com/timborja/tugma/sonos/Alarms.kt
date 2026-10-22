package com.timborja.tugma.sonos

import com.timborja.tugma.upnp.SoapCall
import com.timborja.tugma.upnp.SoapClient
import com.timborja.tugma.upnp.Xml
import com.timborja.tugma.upnp.attr
import com.timborja.tugma.upnp.children
import java.io.IOException

/** Days are 0 = Sunday … 6 = Saturday, as in Sonos' `ON_0123456` recurrence. */
object Recurrence {
    const val ONCE = "ONCE"
    const val DAILY = "DAILY"
    const val WEEKDAYS = "WEEKDAYS"
    const val WEEKENDS = "WEEKENDS"
    private val VALID = Regex("^(ONCE|WEEKDAYS|WEEKENDS|DAILY|ON_[0-6]{1,7})$")
    private val NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun isValid(r: String) = VALID.matches(r)

    fun of(days: Set<Int>): String = when (days) {
        (0..6).toSet() -> DAILY
        (1..5).toSet() -> WEEKDAYS
        setOf(0, 6) -> WEEKENDS
        emptySet<Int>() -> ONCE
        else -> "ON_" + days.sorted().joinToString("")
    }

    fun days(r: String): Set<Int> = when (r) {
        DAILY -> (0..6).toSet()
        WEEKDAYS -> (1..5).toSet()
        WEEKENDS -> setOf(0, 6)
        ONCE -> emptySet()
        else -> r.removePrefix("ON_").mapNotNull { it.digitToIntOrNull() }.filter { it in 0..6 }.toSet()
    }

    fun label(r: String): String = when (r) {
        DAILY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        ONCE -> "Once"
        else -> days(r).sorted().joinToString(", ") { NAMES[it] }
    }

    fun dayName(d: Int) = NAMES[d]
}

/**
 * One Sonos alarm. Times are local `HH:MM:SS`; [durationOrBlank] is how long it plays before
 * stopping ("" = until stopped). Attribute names follow `ListAlarms`; argument names follow
 * SoCo `alarms.py`.
 */
data class SonosAlarm(
    val id: String? = null,
    val startTime: String,
    val durationOrBlank: String = "01:00:00",
    val recurrence: String = Recurrence.DAILY,
    val enabled: Boolean = true,
    val roomUuid: String,
    val programUri: String = BUZZER,
    val programMetaData: String = "",
    val playMode: String = "NORMAL",
    val volume: Int = 20,
    val includeLinkedZones: Boolean = false,
) {
    /** "07:30". */
    val shortTime: String get() = startTime.split(':').take(2).joinToString(":")
    val isChime: Boolean get() = programUri == BUZZER

    fun args(): List<Pair<String, String>> = listOf(
        "StartLocalTime" to startTime,
        "Duration" to durationOrBlank,
        "Recurrence" to recurrence,
        "Enabled" to if (enabled) "1" else "0",
        "RoomUUID" to roomUuid,
        "ProgramURI" to programUri,
        "ProgramMetaData" to programMetaData,
        "PlayMode" to playMode,
        "Volume" to volume.coerceIn(0, 100).toString(),
        "IncludeLinkedZones" to if (includeLinkedZones) "1" else "0",
    )

    fun problems(): List<String> = buildList {
        if (!TIME.matches(startTime)) add("Time must be HH:MM:SS")
        if (durationOrBlank.isNotEmpty() && !TIME.matches(durationOrBlank)) add("Duration must be HH:MM:SS or blank")
        if (!Recurrence.isValid(recurrence)) add("Unknown recurrence $recurrence")
        if (playMode !in PLAY_MODES) add("Unknown play mode $playMode")
        if (roomUuid.isBlank()) add("Pick a room")
    }

    companion object {
        const val BUZZER = "x-rincon-buzzer:0"
        val PLAY_MODES = listOf("NORMAL", "REPEAT_ALL", "SHUFFLE_NOREPEAT", "SHUFFLE", "REPEAT_ONE", "SHUFFLE_REPEAT_ONE")
        private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$")

        fun time(hour: Int, minute: Int) = "%02d:%02d:00".format(hour, minute)

        /** Parses `CurrentAlarmList`. */
        fun parseList(xml: String): List<SonosAlarm> {
            if (xml.isBlank()) return emptyList()
            return Xml.parse(xml).children("Alarm").map { a ->
                SonosAlarm(
                    id = a.attr("ID"),
                    startTime = a.attr("StartTime").orEmpty(),
                    durationOrBlank = a.attr("Duration").orEmpty(),
                    recurrence = a.attr("Recurrence") ?: Recurrence.ONCE,
                    enabled = a.attr("Enabled") == "1",
                    roomUuid = a.attr("RoomUUID").orEmpty(),
                    programUri = a.attr("ProgramURI") ?: BUZZER,
                    programMetaData = a.attr("ProgramMetaData").orEmpty(),
                    playMode = a.attr("PlayMode") ?: "NORMAL",
                    volume = a.attr("Volume")?.toIntOrNull() ?: 20,
                    includeLinkedZones = a.attr("IncludeLinkedZones") == "1",
                )
            }.sortedBy { it.startTime }
        }
    }
}

/** The household's alarms. Any player answers for the whole household. */
class SonosAlarms(private val soap: SoapClient, private val any: Endpoint) {
    private fun call(action: String, args: List<Pair<String, String>> = emptyList()) =
        SoapCall(any.baseUrl, CONTROL, SERVICE, action, args)

    suspend fun list(): List<SonosAlarm> = SonosAlarm.parseList(soap.call(call("ListAlarms"))["CurrentAlarmList"].orEmpty())

    /** Returns the new alarm's ID. */
    suspend fun create(alarm: SonosAlarm): String {
        alarm.problems().firstOrNull()?.let { throw IllegalArgumentException(it) }
        return soap.call(call("CreateAlarm", alarm.args()))["AssignedID"] ?: throw IOException("CreateAlarm returned no ID")
    }

    suspend fun update(alarm: SonosAlarm) {
        val id = alarm.id ?: throw IllegalArgumentException("Alarm has no ID")
        alarm.problems().firstOrNull()?.let { throw IllegalArgumentException(it) }
        soap.call(call("UpdateAlarm", listOf("ID" to id) + alarm.args()))
    }

    suspend fun delete(id: String) {
        soap.call(call("DestroyAlarm", listOf("ID" to id)))
    }

    companion object {
        const val SERVICE = "urn:schemas-upnp-org:service:AlarmClock:1"
        const val CONTROL = "/AlarmClock/Control"
    }
}
