package com.feanor91.treadmill.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.domain.Calories
import com.feanor91.treadmill.domain.UserProfile
import com.feanor91.treadmill.health.BodyComposition
import com.feanor91.treadmill.health.DayBalance
import com.feanor91.treadmill.health.HcAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HealthUi(
    val availability: HcAvailability = HcAvailability.Unsupported,
    /** Accès de base (séances, calories, bilan) accordés. */
    val coreGranted: Boolean = false,
    /** Accès composition corporelle accordés. */
    val bodyGranted: Boolean = false,
    val days: List<DayBalance> = emptyList(),   // aujourd'hui en premier
    val body: BodyComposition = BodyComposition(),
    val loading: Boolean = false,
    val message: String? = null,
)

class HealthViewModel(private val c: AppContainer) : ViewModel() {
    val profile: StateFlow<UserProfile> = c.profileStore.profile
    val useHealthConnect: StateFlow<Boolean> = c.profileStore.useHealthConnect
    val ui = MutableStateFlow(HealthUi())
    val permissions: Set<String> get() = c.health.permissions

    init {
        refresh()
        // Le métabolisme de repos dépend du profil : on recalcule quand il change.
        viewModelScope.launch { profile.drop(1).collect { refreshBalance() } }
    }

    fun refresh() {
        viewModelScope.launch {
            val availability = c.health.availability()
            if (availability != HcAvailability.Available) {
                ui.value = HealthUi(availability = availability)
                return@launch
            }
            ui.value = ui.value.copy(availability = availability, loading = true, message = null)
            runCatching {
                val granted = c.health.granted()
                val core = granted.containsAll(c.health.corePermissions)
                val bodyOk = granted.containsAll(c.health.bodyPermissions)
                val body = if (core) c.health.bodyComposition() else BodyComposition()

                // Health Connect fait foi pour poids et taille quand l'option est active.
                if (core && c.profileStore.useHealthConnect.value) {
                    c.profileStore.update { p ->
                        p.copy(weightKg = body.weightKg ?: p.weightKg, heightCm = body.heightCm ?: p.heightCm)
                    }
                }
                ui.value = HealthUi(availability, core, bodyOk, loadDays(core, body), body)
            }.onFailure {
                ui.value = ui.value.copy(loading = false, message = "Lecture Health Connect impossible : ${it.message}")
            }
        }
    }

    private fun refreshBalance() {
        val cur = ui.value
        if (!cur.coreGranted) return
        viewModelScope.launch {
            runCatching { ui.value = cur.copy(days = loadDays(true, cur.body)) }
        }
    }

    /** Métabolisme de repos : valeur mesurée (balance, Health Connect) sinon formule de Mifflin-St Jeor. */
    private suspend fun loadDays(core: Boolean, body: BodyComposition): List<DayBalance> {
        if (!core) return emptyList()
        val bmr = body.bmrKcalPerDay ?: Calories.bmrPerDay(profile.value)
        val today = LocalDate.now()
        return (0L..6L).map { c.health.dayBalance(today.minusDays(it), bmr) }
    }

    fun setUseHealthConnect(v: Boolean) {
        c.profileStore.setUseHealthConnect(v)
        if (v) refresh()
    }

    fun updateProfile(f: (UserProfile) -> UserProfile) = c.profileStore.update(f)
}
