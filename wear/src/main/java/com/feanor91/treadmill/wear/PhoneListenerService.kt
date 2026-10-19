package com.feanor91.treadmill.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Reçoit les messages du téléphone : début / fin de séance et état à afficher. */
class PhoneListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Protocol.PATH_STATE -> PhoneState.parse(String(event.data, Charsets.UTF_8))?.let { WatchState.phone.value = it }
            Protocol.PATH_WORKOUT_START -> startMeasuringOrAskUser()
            Protocol.PATH_WORKOUT_STOP -> {
                WatchState.phone.value = PhoneState()
                HeartRateService.stop(this)
            }
        }
    }

    /** Démarre la mesure ; si Android l'interdit en arrière-plan, propose de toucher une notification. */
    private fun startMeasuringOrAskUser() {
        val granted = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        if (granted && runCatching { HeartRateService.start(this) }.isSuccess) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ask", "Démarrage séance", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            2,
            NotificationCompat.Builder(this, "ask")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Séance lancée sur le téléphone")
                .setContentText("Touchez pour mesurer le cardio")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
