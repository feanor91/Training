package com.feanor91.treadmill.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Mesure le rythme cardiaque via Health Services (session d'exercice « tapis »,
 * seule méthode fiable en continu poignet au repos écran éteint) et l'envoie au téléphone.
 */
class HeartRateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private var running = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val bpm = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt() ?: return
            WatchState.heartRate.value = bpm
            scope.launch { sendHeartRate(bpm) }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            WatchState.error.value = "Capteur indisponible : ${throwable.message}"
        }
        override fun onAvailabilityChanged(dataType: androidx.health.services.client.data.DataType<*, *>, availability: Availability) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMeasuring()
            return START_NOT_STICKY
        }
        if (!hasSensorPermission() || !enterForeground()) {
            WatchState.error.value = "Autorisez le capteur cardiaque dans l'appli de la montre."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startMeasuring()
        return START_NOT_STICKY
    }

    private fun startMeasuring() {
        running = true
        WatchState.error.value = null
        WatchState.measuring.value = true
        scope.launch {
            runCatching {
                exerciseClient.setUpdateCallback(callback)
                val config = ExerciseConfig.builder(ExerciseType.RUNNING_TREADMILL)
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(false)
                    .build()
                exerciseClient.startExerciseAsync(config).await()
            }.onFailure {
                Log.w(TAG, "Démarrage de la mesure impossible", it)
                WatchState.error.value = "Mesure impossible : ${it.message}"
                stopMeasuring()
            }
        }
    }

    private fun stopMeasuring() {
        running = false
        WatchState.measuring.value = false
        WatchState.heartRate.value = null
        scope.launch {
            runCatching {
                exerciseClient.clearUpdateCallbackAsync(callback).await()
                exerciseClient.endExerciseAsync().await()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        WatchState.measuring.value = false
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun sendHeartRate(bpm: Int) = sendToPhone(this, Protocol.PATH_HEART_RATE, bpm.toString())

    private fun hasSensorPermission() =
        checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(): Boolean {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Mesure cardiaque", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Séance en cours")
            .setContentText("Mesure du rythme cardiaque")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        return try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refusé", e)
            false
        }
    }

    companion object {
        /** Envoie un message texte à tous les appareils connectés (le téléphone). */
        suspend fun sendToPhone(context: Context, path: String, text: String) {
            runCatching {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val client = Wearable.getMessageClient(context)
                nodes.forEach { client.sendMessage(it.id, path, text.toByteArray()).await() }
            }
        }

        private const val TAG = "HeartRateService"
        private const val CHANNEL = "hr"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HeartRateService::class.java))
        }

        fun stop(context: Context) {
            // L'action d'arrêt doit atteindre un service déjà démarré ; sinon il n'y a rien à arrêter.
            if (WatchState.measuring.value) {
                context.startService(Intent(context, HeartRateService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
