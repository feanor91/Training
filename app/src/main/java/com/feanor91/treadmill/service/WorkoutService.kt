package com.feanor91.treadmill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.feanor91.treadmill.MainActivity
import com.feanor91.treadmill.R
import com.feanor91.treadmill.TreadmillApp
import com.feanor91.treadmill.domain.WorkoutState
import com.feanor91.treadmill.domain.WorkoutUi
import com.feanor91.treadmill.ui.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Service au premier plan : garde le processus (et donc la liaison BLE avec le tapis) en vie
 * pendant la séance, écran éteint ou application en arrière-plan. Le moteur reste dans AppContainer.
 */
class WorkoutService : Service() {
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as TreadmillApp).container
        ensureChannel()
        val first = notification(container.engine.ui.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, first)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "treadmill:workout").apply { acquire(4 * 60 * 60 * 1000L) }
        }
        observer?.cancel()
        observer = container.scope.launch {
            container.engine.ui.collect { ui ->
                if (ui.state == WorkoutState.Idle || ui.state == WorkoutState.Finished) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(ui))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(ui: WorkoutUi): Notification {
        val step = ui.currentStep
        val text = buildString {
            append(if (ui.state == WorkoutState.Paused) "En pause · " else "")
            append(formatClock(ui.elapsedSec))
            append(" · ").append(String.format("%.1f km/h", ui.speedKmh))
            if (step != null && step.label.isNotBlank()) append(" · ").append(step.label)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ui.planName.ifBlank { "Séance" })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 1

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
    }
}
