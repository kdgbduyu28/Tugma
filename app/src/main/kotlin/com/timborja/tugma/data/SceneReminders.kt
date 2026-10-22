package com.timborja.tugma.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.timborja.tugma.R
import com.timborja.tugma.TugmaApp
import com.timborja.tugma.sonos.ScenePlanner
import com.timborja.tugma.sonos.SonosScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * Scene reminders: at the scene's time a notification offers to apply it. Nothing changes until
 * the user taps Apply, so group changes stay a deliberate choice. Alarms are inexact on purpose
 * (no exact-alarm permission), so a reminder can arrive a few minutes late while the phone dozes.
 */
object SceneReminders {
    private const val PREFS = "scene_reminders"
    private const val CHANNEL = "scenes"
    const val ACTION_REMIND = "com.timborja.tugma.SCENE_REMIND"
    const val ACTION_APPLY = "com.timborja.tugma.SCENE_APPLY"
    const val EXTRA_ID = "scene"

    private fun pending(context: Context, action: String, sceneId: String) = PendingIntent.getBroadcast(
        context,
        (action + sceneId).hashCode(),
        Intent(context, SceneReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, sceneId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Cancels every alarm set before, then sets one per scene that has a reminder. */
    fun reschedule(context: Context, scenes: List<SonosScene>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet("ids", emptySet()).orEmpty().forEach { am.cancel(pending(context, ACTION_REMIND, it)) }
        val now = ZonedDateTime.now()
        val scheduled = scenes.mapNotNull { s ->
            val at = s.reminder?.next(now) ?: return@mapNotNull null
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending(context, ACTION_REMIND, s.id))
            s.id
        }.toSet()
        prefs.edit { putStringSet("ids", scheduled) }
    }

    fun notify(context: Context, scene: SonosScene, text: String, offerApply: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scene reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(scene.name)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, com.timborja.tugma.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (offerApply) b.addAction(0, "Apply", pending(context, ACTION_APPLY, scene.id))
        runCatching { nm.notify(scene.id.hashCode(), b.build()) } // denied notification permission: nothing to show
    }
}

/** Alarms don't survive a reboot or update; this puts the reminders back. Exported, but only reschedules. */
class SceneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SceneReminders.reschedule(context, (context.applicationContext as TugmaApp).container.scenes.scenes.value)
    }
}

/** Reminder and Apply actions. Not exported: only Tugma's own PendingIntents reach it. */
class SceneReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as TugmaApp).container
        when (intent.action) {
            SceneReminders.ACTION_REMIND -> {
                val scene = c.scenes.scenes.value.firstOrNull { it.id == intent.getStringExtra(SceneReminders.EXTRA_ID) }
                if (scene != null) SceneReminders.notify(context, scene, "Tap Apply to set up ${scene.name}.", offerApply = true)
                SceneReminders.reschedule(context, c.scenes.scenes.value) // next occurrence
            }
            SceneReminders.ACTION_APPLY -> {
                val scene = c.scenes.scenes.value.firstOrNull { it.id == intent.getStringExtra(SceneReminders.EXTRA_ID) } ?: return
                val pending = goAsync()
                scope.launch {
                    try {
                        val text = withTimeoutOrNull(9_000) {
                            if (c.sonos.state.value.household == null) c.sonos.refresh()
                            val h = c.sonos.state.value.household ?: return@withTimeoutOrNull "Couldn't find your Sonos speakers."
                            ScenePlanner.problem(scene, h)?.let { return@withTimeoutOrNull it }
                            val plan = ScenePlanner.plan(scene, h) ?: return@withTimeoutOrNull "Already set up."
                            c.sonos.execute(plan.confirm()).fold({ "Applied." }, { "Didn't apply: ${it.message}" })
                        } ?: "Timed out. Open Tugma and apply it from Scenes."
                        SceneReminders.notify(context, scene, text, offerApply = false)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
