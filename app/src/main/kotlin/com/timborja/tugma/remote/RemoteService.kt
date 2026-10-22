package com.timborja.tugma.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.timborja.tugma.MainActivity
import com.timborja.tugma.R
import com.timborja.tugma.TugmaApp
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.PlaybackControl
import com.timborja.tugma.core.PlaybackStatus
import com.timborja.tugma.core.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controls one network speaker from the notification shade and lock screen. A remote-playback
 * MediaSession means the phone's volume buttons change the speaker's volume, as with the Sonos app.
 * Works for every brand through [PlaybackControl].
 */
class RemoteService : Service() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private var volume: VolumeProvider? = null
    private var poller: Job? = null
    private var brand: Brand? = null
    private var id: String? = null
    private var name: String = ""
    private var last: PlaybackStatus? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val b = intent.getStringExtra(EXTRA_BRAND)?.let { runCatching { Brand.valueOf(it) }.getOrNull() }
                val i = intent.getStringExtra(EXTRA_ID)
                if (b == null || i == null) { stopSelf(); return START_NOT_STICKY }
                start(b, i, intent.getStringExtra(EXTRA_NAME).orEmpty())
            }
            ACTION_PLAY_PAUSE -> act { if (last?.transport == TransportState.PLAYING) pause() else play() }
            ACTION_NEXT -> act { next() }
            ACTION_PREVIOUS -> act { previous() }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            // Restarted by the system with no speaker to control.
            else -> if (id == null) { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun control(): PlaybackControl? {
        val b = brand ?: return null
        val i = id ?: return null
        return (application as TugmaApp).container.source(b)?.playback(i)
    }

    private fun start(b: Brand, i: String, n: String) {
        brand = b
        id = i
        name = n
        last = null
        startInForeground()
        if (session == null) session = MediaSession(this, "tugma-remote").apply { setCallback(callback); isActive = true }
        _active.value = Target(b, i, n)
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MILLIS)
            }
        }
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = act { play() }
        override fun onPause() = act { pause() }
        override fun onSkipToNext() = act { next() }
        override fun onSkipToPrevious() = act { previous() }
        override fun onStop() = act { stop() }
    }

    private fun act(block: suspend PlaybackControl.() -> Unit) {
        val c = control() ?: return
        scope.launch {
            runCatching { c.block() }
            refresh()
        }
    }

    private suspend fun refresh() {
        val c = control()
        val status = c?.let { runCatching { it.status() }.getOrNull() }
        last = status
        val s = session ?: return
        val max = status?.maxVolume ?: 100
        val current = status?.volume
        if (current != null) {
            val v = volume
            if (v == null || v.maxVolume != max) {
                volume = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, max, current) {
                    override fun onSetVolumeTo(value: Int) = setVolume(value.coerceIn(0, max))
                    override fun onAdjustVolume(direction: Int) {
                        if (direction != 0) setVolume((currentVolume + direction * step(max)).coerceIn(0, max))
                    }
                }.also(s::setPlaybackToRemote)
            } else {
                v.currentVolume = current
            }
        }
        val playing = status?.transport == TransportState.PLAYING
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    when (status?.transport) {
                        TransportState.PLAYING -> PlaybackState.STATE_PLAYING
                        TransportState.PAUSED -> PlaybackState.STATE_PAUSED
                        TransportState.TRANSITIONING -> PlaybackState.STATE_BUFFERING
                        null -> PlaybackState.STATE_ERROR
                        else -> PlaybackState.STATE_STOPPED
                    },
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .build(),
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, status?.title ?: name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, status?.artist ?: if (status == null) "Can't reach $name" else name)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status, playing))
    }

    /** Volume buttons move a 0–100 speaker by 2, small ranges by 1. */
    private fun step(max: Int) = if (max >= 50) 2 else 1

    private fun setVolume(value: Int) {
        volume?.currentVolume = value
        val c = control() ?: return
        scope.launch { runCatching { c.setVolume(value) } }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speaker remote", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null, false), type)
    }

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, RemoteService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(status: PlaybackStatus?, playing: Boolean): Notification {
        fun action(icon: Int, label: String, intent: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, intent).build()
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(status?.title ?: name.ifBlank { "Speaker" })
            .setContentText(listOfNotNull(status?.artist, name.takeIf { status?.title != null }).joinToString(" · ").ifBlank { "Tugma remote" })
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", pending(ACTION_PREVIOUS, 1)))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", pending(ACTION_PLAY_PAUSE, 2))
                else action(android.R.drawable.ic_media_play, "Play", pending(ACTION_PLAY_PAUSE, 2)),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", pending(ACTION_NEXT, 3)))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", pending(ACTION_STOP, 4)))
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        _active.value = null
        super.onDestroy()
    }

    data class Target(val brand: Brand, val id: String, val name: String)

    companion object {
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 2
        private const val POLL_MILLIS = 5_000L
        private const val ACTION_START = "com.timborja.tugma.remote.START"
        private const val ACTION_PLAY_PAUSE = "com.timborja.tugma.remote.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.timborja.tugma.remote.NEXT"
        private const val ACTION_PREVIOUS = "com.timborja.tugma.remote.PREVIOUS"
        private const val ACTION_STOP = "com.timborja.tugma.remote.STOP"
        private const val EXTRA_BRAND = "brand"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        private val _active = MutableStateFlow<Target?>(null)

        /** The speaker the notification currently controls, or null. */
        val active: StateFlow<Target?> = _active.asStateFlow()

        fun start(context: Context, brand: Brand, id: String, name: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_BRAND, brand.name).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }
    }
}
