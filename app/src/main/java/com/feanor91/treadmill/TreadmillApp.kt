package com.feanor91.treadmill

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.feanor91.treadmill.ble.FtmsClient
import com.feanor91.treadmill.ble.HeartRateClient
import com.feanor91.treadmill.data.AppDatabase
import com.feanor91.treadmill.data.TrainingRepository
import com.feanor91.treadmill.domain.WorkoutEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Racine de composition manuelle : un seul client BLE et un seul moteur de séance pour tout le processus. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "training.db").build()
    val repository = TrainingRepository(db)

    val ftms = FtmsClient(app, scope)
    val heartRate = HeartRateClient(app)

    val engine = WorkoutEngine(ftms, heartRate, scope) { summary ->
        // Les sorties trop courtes (< 30 s) ne polluent pas l'historique.
        if (summary.durationSec >= 30) repository.recordSession(summary)
    }

    init {
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
        const val SEED_VERSION = 2
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
