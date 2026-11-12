package com.timborja.tugma.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.timborja.tugma.R
import com.timborja.tugma.core.ShortcutAction
import com.timborja.tugma.core.Shortcuts
import com.timborja.tugma.core.TransportState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick Settings tile for the widget's speaker: tap to play or pause, with what's playing as the
 * subtitle. With no speaker picked yet, a tap opens the app.
 */
class SpeakerTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
        if (SpeakerWidget.target(this) != null) run(null)
    }

    override fun onClick() {
        super.onClick()
        if (SpeakerWidget.target(this) == null) openApp() else run(ShortcutAction.PLAY_PAUSE)
    }

    /** Reads (and with [action], changes) the speaker, then shows the result on the tile and widget. */
    private fun run(action: ShortcutAction?) {
        val target = SpeakerWidget.target(this) ?: return
        val context = applicationContext
        SpeakerWidget.scope.launch {
            withTimeoutOrNull(9_000) {
                val control = SpeakerWidget.control(context, target)
                if (control == null) {
                    SpeakerWidget.save(context, "Can't find ${target.name}", null)
                    return@withTimeoutOrNull
                }
                val before = runCatching { control.status() }.getOrNull()
                if (action != null) runCatching { Shortcuts.perform(control, action, before) }
                val after = if (action == null) before else runCatching { control.status() }.getOrNull()
                SpeakerWidget.save(context, Shortcuts.summary(after, target.name), after?.transport == TransportState.PLAYING)
            }
            SpeakerWidget.updateAll(context)
            render()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = SpeakerWidget.openIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val target = SpeakerWidget.target(this)
        val (line, playing) = SpeakerWidget.lastShown(this)
        tile.label = target?.name ?: getString(R.string.speaker_tile_label)
        tile.state = when {
            target == null -> Tile.STATE_INACTIVE
            playing -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.icon = Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (target == null) getString(R.string.widget_pick) else line.ifEmpty { null }
        }
        tile.contentDescription = listOfNotNull(target?.name, line.ifEmpty { null }).joinToString(", ").ifEmpty { getString(R.string.speaker_tile_label) }
        tile.updateTile()
    }

    companion object {
        /** Asks the system to rebind the tile so it redraws (it's a no-op while the shade is closed). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, SpeakerTileService::class.java)) }
        }
    }
}
