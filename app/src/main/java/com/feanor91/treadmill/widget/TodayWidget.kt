package com.feanor91.treadmill.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.feanor91.treadmill.MainActivity
import com.feanor91.treadmill.R
import com.feanor91.treadmill.TreadmillApp
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.ui.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widget d'accueil : affiche la séance du jour et la lance d'un appui. */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val container = (context.applicationContext as TreadmillApp).container
        container.scope.launch(Dispatchers.IO) {
            try { render(context, manager, appWidgetIds) } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_START_SCHEDULE_ID = "startScheduleId"
        const val EXTRA_START_FREE = "startFree"

        /** Met à jour tous les widgets posés (appelé quand le calendrier ou l'historique change). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val container = (context.applicationContext as TreadmillApp).container
            container.scope.launch(Dispatchers.IO) { render(context, manager, ids) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val container = (context.applicationContext as TreadmillApp).container
            val item = runCatching { container.repository.firstOpenToday() }.getOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_today)

            val startIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (item != null) {
                val plan = item.plan
                views.setTextViewText(R.id.widget_kicker, "SÉANCE DU JOUR")
                views.setTextViewText(R.id.widget_title, plan.plan.name)
                views.setTextViewText(
                    R.id.widget_sub,
                    "${formatClock(plan.totalSec)} · ${"%.1f".format(plan.distanceKm)} km · ${plan.toWorkoutSteps().size} paliers",
                )
                startIntent.putExtra(EXTRA_START_SCHEDULE_ID, item.schedule.id)
            } else {
                views.setTextViewText(R.id.widget_kicker, "AUJOURD'HUI")
                views.setTextViewText(R.id.widget_title, "Pas de séance prévue")
                views.setTextViewText(R.id.widget_sub, "Touchez ▶ pour une course libre")
                startIntent.putExtra(EXTRA_START_FREE, true)
            }
            val startPi = PendingIntent.getActivity(
                context, (item?.schedule?.id ?: 0L).toInt() + 1, startIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_start, startPi)

            val openPi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPi)
            manager.updateAppWidget(ids, views)
        }
    }
}
