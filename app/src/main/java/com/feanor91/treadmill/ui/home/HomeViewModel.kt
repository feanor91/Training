package com.feanor91.treadmill.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.data.ScheduledItem
import com.feanor91.treadmill.domain.BleDevice
import com.feanor91.treadmill.domain.ConnectionState
import com.feanor91.treadmill.domain.WorkoutStep
import com.feanor91.treadmill.health.StepsToday
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ScanTarget { Treadmill, HeartRate }

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.ftms.state
    val limits = c.ftms.limits
    val metrics = c.ftms.metrics
    val deviceName = c.ftms.deviceName
    val ftmsError = c.ftms.error
    val strapName = c.heartRate.deviceName
    val strapHr = c.heartRate.heartRate
    val watchConnected = c.wear.watchConnected
    val watchName = c.wear.watchName
    val watchHr = c.wear.watchHr

    val today: StateFlow<List<ScheduledItem>> =
        c.repository.today().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pas du jour, relus toutes les 30 s tant que l'accueil est affiché. */
    val steps: StateFlow<StepsToday> = flow {
        while (true) {
            emit(c.health.stepsToday())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepsToday())

    val devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanTarget = MutableStateFlow<ScanTarget?>(null)
    val scanError = MutableStateFlow<String?>(null)
    private var scanJob: Job? = null

    fun startScan(target: ScanTarget) {
        scanJob?.cancel()
        devices.value = emptyList()
        scanError.value = null
        scanTarget.value = target
        val flow = if (target == ScanTarget.Treadmill) c.ftms.scan() else c.heartRate.scan()
        scanJob = viewModelScope.launch {
            flow.catch { scanError.value = it.message }.collect { devices.value = it }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanTarget.value = null
    }

    fun connect(device: BleDevice) {
        val target = scanTarget.value ?: return
        stopScan()
        // La connexion doit survivre au ViewModel : on la lance dans la portée applicative.
        c.scope.launch {
            if (target == ScanTarget.Treadmill) c.ftms.connect(device) else c.heartRate.connect(device)
        }
    }

    fun disconnectTreadmill() = c.ftms.disconnect()
    fun disconnectStrap() = c.heartRate.disconnect()
    fun dismissError() = c.ftms.clearError()

    /** Course libre : un palier sans limite de durée, à vitesse douce ; on ajuste ensuite en direct. */
    fun freeRunSteps(): List<WorkoutStep> {
        val l = c.ftms.limits.value
        return listOf(WorkoutStep(0, l.clampSpeed(2.0), l.clampIncline(0.0), "Libre"))
    }
}
