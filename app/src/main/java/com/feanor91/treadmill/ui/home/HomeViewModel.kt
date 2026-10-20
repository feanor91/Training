package com.feanor91.treadmill.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.data.PlanWithSteps
import com.feanor91.treadmill.data.ScheduledItem
import com.feanor91.treadmill.domain.WorkoutStep
import com.feanor91.treadmill.health.StepsToday
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.ftms.state
    val limits = c.ftms.limits
    val strapName = c.heartRate.deviceName
    val watchConnected = c.wear.watchConnected

    val today: StateFlow<List<ScheduledItem>> =
        c.repository.today().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<PlanWithSteps>> =
        c.repository.plans.map { list -> list.filter { it.plan.favorite } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pas du jour, relus toutes les 30 s tant que l'accueil est affiché. */
    val steps: StateFlow<StepsToday> = flow {
        while (true) {
            emit(c.health.stepsToday())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepsToday())

    /** Course libre : un palier sans limite de durée, à vitesse douce ; on ajuste ensuite en direct. */
    fun freeRunSteps(): List<WorkoutStep> {
        val l = c.ftms.limits.value
        return listOf(WorkoutStep(0, l.clampSpeed(2.0), l.clampIncline(0.0), "Libre"))
    }
}
