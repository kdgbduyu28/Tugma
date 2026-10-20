package com.timborja.tugma.eq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.timborja.tugma.MainActivity
import com.timborja.tugma.R
import com.timborja.tugma.TugmaApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the EQ attached while it's switched on: to the global output mix where the phone allows
 * it, and to every audio session music apps announce. Picks the profile for the current output.
 */
class EqService : Service() {
    private val scope = MainScope()
    private val chains = mutableMapOf<Int, EffectChain>()
    private val packages = mutableMapOf<Int, String>()
    private var profile: EqProfile = EqPresets.FLAT
    private var outputLabel = ""
    private lateinit var repo: EqRepository

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(EffectSessions.EXTRA_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                EffectSessions.OPEN -> {
                    packages[session] = intent.getStringExtra(EffectSessions.EXTRA_PACKAGE).orEmpty()
                    attach(session)
                }
                EffectSessions.CLOSE -> {
                    chains.remove(session)?.release()
                    packages.remove(session)
                }
            }
            publish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as TugmaApp).container
        repo = container.eq
        startInForeground()
        ContextCompat.registerReceiver(
            this, sessionReceiver,
            IntentFilter().apply { addAction(EffectSessions.OPEN); addAction(EffectSessions.CLOSE) },
            ContextCompat.RECEIVER_EXPORTED, // sent by other apps' players
        )
        attachGlobal()
        scope.launch {
            combine(repo.library, repo.preview, container.output.current) { lib, preview, out ->
                Triple(preview ?: lib.profileFor(out.key), out, preview != null)
            }.collect { (p, out, previewing) ->
                profile = p
                outputLabel = out.label + if (previewing) " (previewing)" else ""
                chains.values.forEach { chain -> runCatching { chain.apply(p) } }
                publish()
                notifyStatus()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(sessionReceiver) }
        chains.values.forEach { runCatching { it.release() } }
        chains.clear()
        repo.updateStatus { EqStatus() }
        super.onDestroy()
    }

    private fun attachGlobal() {
        try {
            chains[GLOBAL] = EffectChain.create(GLOBAL, profile).also { it.apply(profile) }
            repo.updateStatus { it.copy(globalAttached = true, globalError = null) }
        } catch (e: Exception) {
            // Some phones refuse effects on the output mix; per-app sessions still work.
            repo.updateStatus { it.copy(globalAttached = false, globalError = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun attach(session: Int) {
        if (session in chains) return
        runCatching { chains[session] = EffectChain.create(session, profile).also { it.apply(profile) } }
    }

    private fun publish() {
        repo.updateStatus {
            it.copy(
                running = true,
                sessions = packages.filterKeys { s -> s in chains },
                profileName = profile.name,
                engine = chains.values.firstOrNull()?.engineName,
            )
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Equalizer", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notifyStatus() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_eq)
        .setContentTitle("EQ on · ${profile.name}")
        .setContentText(outputLabel.ifBlank { "Equalizer running" })
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private companion object {
        const val GLOBAL = 0
        const val CHANNEL = "eq"
        const val NOTIFICATION_ID = 7
    }
}
