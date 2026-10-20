package com.feanor91.treadmill.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.feanor91.treadmill.MainActivity
import com.feanor91.treadmill.R
import com.feanor91.treadmill.TreadmillApp
import com.feanor91.treadmill.data.PlanWithSteps
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.ui.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        const val EXTRA_START_PLAN_ID = "startPlanId"

        /** Met à jour tous les widgets posés (appelé quand le calendrier ou l'historique change). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val container = (context.applicationContext as TreadmillApp).container
            container.scope.launch(Dispatchers.IO) { render(context, manager, ids) }
        }

        /** Favoris, ou tous les entraînements s'il n'y en a aucun : (liste, vrai si ce sont les favoris). */
        suspend fun plansForList(context: Context): Pair<List<PlanWithSteps>, Boolean> {
            val repo = (context.applicationContext as TreadmillApp).container.repository
            val all = runCatching { repo.plansOnce() }.getOrDefault(emptyList())
            val favorites = all.filter { it.plan.favorite }
            return if (favorites.isNotEmpty()) favorites to true else all to false
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val container = (context.applicationContext as TreadmillApp).container
            val item = runCatching { container.repository.firstOpenToday() }.getOrNull()
            val activityFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

            val openPi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            if (item != null) {
                // Jour avec séance : grande carte « séance du jour » avec bouton ▶.
                val plan = item.plan
                val views = RemoteViews(context.packageName, R.layout.widget_today)
                views.setTextViewText(R.id.widget_kicker, "SÉANCE DU JOUR")
                views.setTextViewText(R.id.widget_title, plan.plan.name)
                views.setTextViewText(
                    R.id.widget_sub,
                    "${formatClock(plan.totalSec)} · ${"%.1f".format(plan.distanceKm)} km · ${plan.toWorkoutSteps().size} paliers",
                )
                val startIntent = Intent(context, MainActivity::class.java).addFlags(activityFlags)
                    .putExtra(EXTRA_START_SCHEDULE_ID, item.schedule.id)
                views.setOnClickPendingIntent(
                    R.id.widget_start,
                    PendingIntent.getActivity(context, item.schedule.id.toInt() + 1, startIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
                views.setOnClickPendingIntent(R.id.widget_root, openPi)
                manager.updateAppWidget(ids, views)
                return
            }

            // Jour sans séance : liste défilante des favoris (ou de tous les entraînements).
            val (_, favorites) = plansForList(context)
            val views = RemoteViews(context.packageName, R.layout.widget_list)
            views.setTextViewText(R.id.widget_kicker, "PAS DE SÉANCE PRÉVUE")
            views.setTextViewText(R.id.widget_title, if (favorites) "Mes favoris" else "Mes entraînements")
            val freeIntent = Intent(context, MainActivity::class.java).addFlags(activityFlags).putExtra(EXTRA_START_FREE, true)
            views.setOnClickPendingIntent(
                R.id.widget_start,
                PendingIntent.getActivity(context, 1, freeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPi)
            views.setRemoteAdapter(R.id.widget_list, Intent(context, TodayWidgetService::class.java))
            // Modèle d'intention complété par chaque ligne (extra du plan) : doit être modifiable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context, 2, Intent(context, MainActivity::class.java).addFlags(activityFlags),
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                ),
            )
            manager.updateAppWidget(ids, views)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }
}

/** Fournit les lignes (favoris ou tous les entraînements) de la liste du widget. */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var items: List<PlanWithSteps> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            // Appelé sur un thread de liaison : on peut bloquer le temps de la lecture en base.
            items = runBlocking { TodayWidgetProvider.plansForList(context).first }.take(30)
        }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews? {
            val p = items.getOrNull(position) ?: return null
            return RemoteViews(context.packageName, R.layout.widget_list_item).apply {
                setTextViewText(R.id.widget_item_title, p.plan.name)
                setTextViewText(R.id.widget_item_sub, "${formatClock(p.totalSec)} · ${"%.1f".format(p.distanceKm)} km")
                setOnClickFillInIntent(R.id.widget_item_root, Intent().putExtra(TodayWidgetProvider.EXTRA_START_PLAN_ID, p.plan.id))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.plan?.id ?: position.toLong()
        override fun hasStableIds() = true
    }
}
