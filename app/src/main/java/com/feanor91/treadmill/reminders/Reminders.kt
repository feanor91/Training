package com.feanor91.treadmill.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.feanor91.treadmill.MainActivity
import com.feanor91.treadmill.R
import com.feanor91.treadmill.TreadmillApp
import com.feanor91.treadmill.data.TrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Rappels des séances d'un programme à l'heure choisie. Une seule alarme est armée à la fois (la prochaine
 * séance non faite) ; elle est réarmée à son déclenchement, au démarrage de l'appli, après un redémarrage
 * du téléphone et à chaque création / suppression de programme.
 */
class ReminderScheduler(private val context: Context, private val repository: TrainingRepository) {

    suspend fun reschedule() {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val next = repository.reminderRows(LocalDate.now().toEpochDay())
            .map { it to LocalDateTime.of(LocalDate.ofEpochDay(it.epochDay), java.time.LocalTime.of(it.reminderMinutes / 60, it.reminderMinutes % 60))
                .atZone(zone).toInstant().toEpochMilli() }
            .filter { it.second > now }
            .minByOrNull { it.second }

        val intent = Intent(context, ReminderReceiver::class.java)
        if (next == null) {
            alarms.cancel(PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return)
            return
        }
        val (row, at) = next
        intent.putExtra(EXTRA_SCHEDULE_ID, row.scheduleId).putExtra(EXTRA_PLAN_NAME, row.planName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Alarme exacte si le système l'autorise, sinon alarme tolérant quelques minutes de retard (aucune permission).
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure {
            Log.w(TAG, "Alarme exacte refusée, repli inexact", it)
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    companion object {
        const val TAG = "Reminders"
        const val CHANNEL = "reminders"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_PLAN_NAME = "planName"
    }
}

/** Affiche la notification du rappel puis arme le suivant. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TreadmillApp).container
        val scheduleId = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULE_ID, -1)
        val planName = intent.getStringExtra(ReminderScheduler.EXTRA_PLAN_NAME) ?: "Séance d'entraînement"
        val pending = goAsync()
        container.scope.launch(Dispatchers.IO) {
            try {
                // Pas de rappel si la séance a déjà été faite (ou supprimée) entre-temps.
                if (scheduleId >= 0 && !container.repository.isScheduleCompleted(scheduleId)) notify(context, planName)
                container.reminders.reschedule()
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, planName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL, "Rappels d'entraînement", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            100,
            NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("C'est l'heure de votre séance")
                .setContentText("$planName — touchez pour ouvrir l'appli")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}

/** Réarme le rappel après un redémarrage du téléphone ou une mise à jour de l'application. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TreadmillApp).container
        val pending = goAsync()
        container.scope.launch(Dispatchers.IO) {
            try { container.reminders.reschedule() } finally { pending.finish() }
        }
    }
}
