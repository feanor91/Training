package com.feanor91.treadmill

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import androidx.room.Room
import com.feanor91.treadmill.ble.FtmsClient
import com.feanor91.treadmill.ble.HeartRateClient
import com.feanor91.treadmill.data.AppDatabase
import com.feanor91.treadmill.data.DeviceStore
import com.feanor91.treadmill.data.ProfileStore
import com.feanor91.treadmill.domain.Calories
import com.feanor91.treadmill.domain.ConnectionState
import kotlinx.coroutines.delay
import com.feanor91.treadmill.health.ExportableSession
import com.feanor91.treadmill.health.HealthConnectGateway
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.domain.WorkoutStep
import com.feanor91.treadmill.reminders.ReminderScheduler
import com.feanor91.treadmill.widget.TodayWidgetProvider
import com.feanor91.treadmill.wear.WearBridge
import com.feanor91.treadmill.data.TrainingRepository
import com.feanor91.treadmill.domain.WorkoutEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Racine de composition manuelle : un seul client BLE et un seul moteur de séance pour tout le processus. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "training.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
        .build()
    val repository = TrainingRepository(db)

    val reminders = ReminderScheduler(app, repository)

    val ftms = FtmsClient(app, scope)
    val heartRate = HeartRateClient(app)

    val profileStore = ProfileStore(app)
    val deviceStore = DeviceStore(app)
    val health = HealthConnectGateway(app)

    val wear = WearBridge(app, scope, autoOpenWatch = { profileStore.autoOpenWatch.value })

    /** Résultat de l'export Health Connect de la dernière séance (affiché dans le bilan de fin). */
    val exportStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val engine = WorkoutEngine(
        ftms, externalHeartRate = { heartRate.heartRate.value ?: wear.watchHr.value }, scope,
        profile = { profileStore.profile.value },
    ) { summary ->
        // Les sorties trop courtes (< 30 s) ne polluent pas l'historique.
        if (summary.durationSec >= 30) {
            exportStatus.value = "Envoi à Health Connect…"
            val id = repository.recordSession(summary)
            // Export automatique à la fin de chaque séance ; sinon réessayable depuis l'historique.
            exportStatus.value = runCatching {
                val result = health.export(
                    ExportableSession(
                        summary.planName, summary.startedAt, summary.durationSec, summary.distanceM,
                        summary.activeKcal, summary.avgSpeedKmh, summary.steps,
                        restingKcal = (Calories.bmrPerDay(profileStore.profile.value) * summary.durationSec / 86_400.0).toInt(),
                        samples = summary.samples,
                    ),
                )
                refreshWidget()
                if (result.sent) {
                    repository.markExported(id)
                    "✓ Séance envoyée à Health Connect" + result.report.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                }
                else "Non envoyée à Health Connect : autorisez-le dans l'onglet Santé, puis utilisez « Envoyer » dans l'historique."
            }.getOrElse { "Envoi à Health Connect impossible (${it.message}) : réessayez depuis l'historique." }
        } else {
            exportStatus.value = "Séance trop courte (moins de 30 s) : non enregistrée."
        }
    }

    fun refreshWidget() = TodayWidgetProvider.refresh(app)

    private var reconnecting = false

    private fun canUseBluetooth() = ftms.bluetoothEnabled &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    /**
     * Reconnecte le dernier tapis et le dernier capteur cardiaque connus. Appelé au démarrage de l'application
     * et à chaque retour au premier plan : après une mise à jour, plus besoin de refaire l'association.
     * Silencieux : un tapis éteint n'affiche pas d'erreur.
     */
    fun tryReconnect() {
        if (reconnecting || !deviceStore.autoReconnect.value || !canUseBluetooth()) return
        reconnecting = true
        scope.launch {
            try {
                val tm = deviceStore.treadmill.value
                if (tm != null && ftms.state.value == ConnectionState.Disconnected && !engine.running) {
                    for (attempt in 1..2) {
                        if (ftms.connect(tm, silent = true)) break
                        delay(4_000)
                    }
                }
                val strap = deviceStore.strap.value
                if (strap != null && heartRate.deviceName.value == null) heartRate.connect(strap)
            } finally {
                reconnecting = false
            }
        }
    }

    private var startingFromShortcut = false

    /**
     * Widget et notification lancent l'appli à froid : le tapis mémorisé n'est pas encore reconnecté, et une séance
     * démarrée tout de suite serait simulée (aucune commande Start, donc pas de compte à rebours sur le tapis).
     * On attend donc sa connexion, quelques secondes au plus.
     */
    private suspend fun awaitSavedTreadmill() {
        if (deviceStore.treadmill.value == null || ftms.state.value == ConnectionState.Ready) return
        if (!deviceStore.autoReconnect.value || !canUseBluetooth()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, "Connexion au tapis…", android.widget.Toast.LENGTH_SHORT).show()
        }
        tryReconnect()
        withTimeoutOrNull(RECONNECT_WAIT_MS) { ftms.state.first { it == ConnectionState.Ready } }
    }

    /** Démarre la séance du calendrier [scheduleId] (ou une course libre) : utilisé par le widget et la notification. */
    suspend fun startFromWidget(scheduleId: Long, free: Boolean, planId: Long = -1): Boolean {
        if (engine.running || startingFromShortcut) return false
        startingFromShortcut = true
        try {
            awaitSavedTreadmill()
            return startNow(scheduleId, free, planId)
        } finally {
            startingFromShortcut = false
        }
    }

    private suspend fun startNow(scheduleId: Long, free: Boolean, planId: Long): Boolean {
        if (engine.running) return false
        if (planId >= 0) {
            val plan = repository.plan(planId) ?: return false
            engine.start(plan.plan.name, plan.toWorkoutSteps(), null)
        } else if (scheduleId >= 0) {
            val item = repository.scheduledItem(scheduleId) ?: return false
            engine.start(item.plan.plan.name, item.plan.toWorkoutSteps(), item.schedule.id)
        } else if (free) {
            val l = ftms.limits.value
            engine.start("Course libre", listOf(WorkoutStep(0, l.clampSpeed(2.0), l.clampIncline(0.0), "Libre")))
        } else return false
        return true
    }

    init {
        wear.attach(engine)
        tryReconnect()
        scope.launch { runCatching { refreshWidget() } }
        scope.launch { runCatching { reminders.reschedule() } }
        val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getInt("seedVersion", 0) < SEED_VERSION) {
            scope.launch {
                repository.seedDefaults()
                prefs.edit().putInt("seedVersion", SEED_VERSION).apply()
            }
        }
    }

    private companion object {
        /** À incrémenter quand de nouveaux plans par défaut sont ajoutés. */
        const val SEED_VERSION = 4

        /** Attente maximale de la reconnexion du tapis avant de démarrer quand même (séance simulée). */
        const val RECONNECT_WAIT_MS = 15_000L
    }
}

class TreadmillApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
