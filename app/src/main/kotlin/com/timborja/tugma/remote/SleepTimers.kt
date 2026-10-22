package com.timborja.tugma.remote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.timborja.tugma.TugmaApp
import com.timborja.tugma.core.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sleep timers kept by the phone, for brands without one of their own (DLNA, Linkplay, Cast).
 * An inexact alarm fires a receiver that pauses the speaker; it may run a minute or two late
 * while the phone dozes. Sonos uses the speaker's own timer instead.
 */
object SleepTimers {
    private const val PREFS = "sleep_timers"
    private const val ACTION = "com.timborja.tugma.SLEEP_TIMER"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(brand: Brand, id: String) = "${brand.name}|$id"

    private fun intent(context: Context, brand: Brand, id: String) =
        PendingIntent.getBroadcast(
            context,
            key(brand, id).hashCode(),
            Intent(context, SleepTimerReceiver::class.java).setAction(ACTION).putExtra("brand", brand.name).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun set(context: Context, brand: Brand, id: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes.coerceIn(1, 24 * 60) * 60_000L
        prefs(context).edit { putLong(key(brand, id), at) }
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, brand, id))
    }

    fun cancel(context: Context, brand: Brand, id: String) {
        prefs(context).edit { remove(key(brand, id)) }
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, brand, id))
    }

    /** Milliseconds left, or null when no timer is set (or it already fired). */
    fun remainingMillis(context: Context, brand: Brand, id: String): Long? =
        prefs(context).getLong(key(brand, id), 0).takeIf { it > 0 }?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 }

    internal fun clear(context: Context, brand: Brand, id: String) = prefs(context).edit { remove(key(brand, id)) }
}

/** Pauses the speaker when its timer fires, rescanning its brand first if the app process was killed. */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val brand = intent.getStringExtra("brand")?.let { runCatching { Brand.valueOf(it) }.getOrNull() } ?: return
        val id = intent.getStringExtra("id") ?: return
        SleepTimers.clear(context, brand, id)
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(9_000) {
                    val source = (context.applicationContext as TugmaApp).container.source(brand) ?: return@withTimeoutOrNull
                    val control = source.playback(id) ?: run { runCatching { source.refresh() }; source.playback(id) } ?: return@withTimeoutOrNull
                    if (runCatching { control.pause() }.isFailure) runCatching { control.stop() }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
