package com.timborja.tugma.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.edit
import com.timborja.tugma.MainActivity
import com.timborja.tugma.R
import com.timborja.tugma.TugmaApp
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home-screen widget for one speaker of any brand: what's playing, play/pause, next and volume.
 * The speaker is picked on its screen in the app. Taps run in the background (`goAsync`) and
 * rescan first if the app process was killed.
 */
class SpeakerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        act(context, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action in ACTIONS) act(context, action)
    }

    private fun act(context: Context, action: String?) {
        val target = target(context) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(9_000) {
                    val control = control(context, target) ?: run { save(context, "Can't find ${target.name}", null); return@withTimeoutOrNull }
                    val before = runCatching { control.status() }.getOrNull()
                    runCatching {
                        when (action) {
                            ACTION_PLAY_PAUSE -> if (before?.transport == TransportState.PLAYING) control.pause() else control.play()
                            ACTION_NEXT -> control.next()
                            ACTION_VOL_UP, ACTION_VOL_DOWN -> before?.volume?.let { v ->
                                val step = if (before.maxVolume >= 50) 5 else 1
                                control.setVolume((v + if (action == ACTION_VOL_UP) step else -step).coerceIn(0, before.maxVolume))
                            }
                        }
                    }
                    val after = runCatching { control.status() }.getOrNull()
                    val subtitle = when {
                        after == null -> "Can't reach ${target.name}"
                        after.title != null -> listOfNotNull(after.title, after.artist).joinToString(" · ")
                        else -> after.volume?.let { "Volume $it" } ?: ""
                    }
                    save(context, subtitle, after?.transport == TransportState.PLAYING)
                }
            } finally {
                updateAll(context)
                pending.finish()
            }
        }
    }

    /** Finds the speaker, rescanning its brand once when the process started cold. */
    private suspend fun control(context: Context, t: Target): PlaybackControl? {
        val source = (context.applicationContext as TugmaApp).container.source(t.brand) ?: return null
        source.playback(t.id)?.let { return it }
        runCatching { source.refresh() }
        return source.playback(t.id)
    }

    data class Target(val brand: Brand, val id: String, val name: String)

    companion object {
        private const val PREFS = "speaker_widget"
        private const val ACTION_PLAY_PAUSE = "com.timborja.tugma.widget.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.timborja.tugma.widget.NEXT"
        private const val ACTION_VOL_UP = "com.timborja.tugma.widget.VOL_UP"
        private const val ACTION_VOL_DOWN = "com.timborja.tugma.widget.VOL_DOWN"
        private val ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_VOL_UP, ACTION_VOL_DOWN)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun target(context: Context): Target? {
            val p = prefs(context)
            val brand = p.getString("brand", null)?.let { runCatching { Brand.valueOf(it) }.getOrNull() } ?: return null
            val id = p.getString("id", null) ?: return null
            return Target(brand, id, p.getString("name", null).orEmpty())
        }

        /** Points every Tugma widget at this speaker. */
        fun setTarget(context: Context, brand: Brand, id: String, name: String) {
            prefs(context).edit { putString("brand", brand.name); putString("id", id); putString("name", name); remove("subtitle"); remove("playing") }
            updateAll(context)
            context.sendBroadcast(Intent(context, SpeakerWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids(context)))
        }

        fun isTarget(context: Context, brand: Brand, id: String): Boolean = target(context)?.let { it.brand == brand && it.id == id } == true

        private fun save(context: Context, subtitle: String, playing: Boolean?) {
            prefs(context).edit { putString("subtitle", subtitle); if (playing != null) putBoolean("playing", playing) }
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, SpeakerWidget::class.java))

        fun updateAll(context: Context) = render(context, AppWidgetManager.getInstance(context), ids(context))

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val p = prefs(context)
            val t = target(context)
            val views = RemoteViews(context.packageName, R.layout.widget_speaker)
            views.setTextViewText(R.id.widget_title, t?.name ?: context.getString(R.string.widget_pick))
            views.setTextViewText(R.id.widget_subtitle, if (t == null) "Open a speaker, then tap “Show on widget”" else p.getString("subtitle", "").orEmpty())
            views.setImageViewResource(R.id.widget_play, if (p.getBoolean("playing", false)) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            fun tap(action: String, code: Int) = PendingIntent.getBroadcast(
                context, code, Intent(context, SpeakerWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_play, tap(ACTION_PLAY_PAUSE, 1))
            views.setOnClickPendingIntent(R.id.widget_next, tap(ACTION_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_vol_up, tap(ACTION_VOL_UP, 3))
            views.setOnClickPendingIntent(R.id.widget_vol_down, tap(ACTION_VOL_DOWN, 4))
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
