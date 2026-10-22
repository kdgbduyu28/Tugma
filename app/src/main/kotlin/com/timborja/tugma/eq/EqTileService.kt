package com.timborja.tugma.eq

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.timborja.tugma.MainActivity
import com.timborja.tugma.R
import com.timborja.tugma.TugmaApp

/** Quick Settings tile: turns the phone-side EQ on or off without opening the app. */
class EqTileService : TileService() {
    private val eq get() = (application as TugmaApp).container.eq

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val turnOn = !eq.enabled.value
        try {
            eq.setEnabled(turnOn)
        } catch (e: IllegalStateException) {
            // Some phones refuse to start a foreground service from a tile; open the app instead.
            eq.setEnabled(false)
            openApp()
        }
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = eq.enabled.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.eq_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_eq)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) eq.status.value.profileName ?: "On" else "Off"
        }
        tile.updateTile()
    }
}
