package com.feanor91.treadmill.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.domain.UserProfile
import com.feanor91.treadmill.health.DayBalance
import com.feanor91.treadmill.health.HcAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HealthUi(
    val availability: HcAvailability = HcAvailability.Unsupported,
    val permissionsGranted: Boolean = false,
    val days: List<DayBalance> = emptyList(),   // aujourd'hui en premier
    val loading: Boolean = false,
    val message: String? = null,
)

class HealthViewModel(private val c: AppContainer) : ViewModel() {
    val profile: StateFlow<UserProfile> = c.profileStore.profile
    val ui = MutableStateFlow(HealthUi())
    val permissions: Set<String> get() = c.health.permissions

    init {
        refresh()
        // Le métabolisme de repos dépend du profil : on recalcule quand il change.
        viewModelScope.launch { profile.drop(1).collect { refresh() } }
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
                val granted = c.health.granted().containsAll(c.health.permissions)
                val days = if (granted) {
                    val today = LocalDate.now()
                    (0L..6L).map { c.health.dayBalance(today.minusDays(it), profile.value) }
                } else emptyList()
                ui.value = HealthUi(availability, granted, days)
            }.onFailure {
                ui.value = ui.value.copy(loading = false, message = "Lecture Health Connect impossible : ${it.message}")
            }
        }
    }

    fun importBody() {
        viewModelScope.launch {
            runCatching { c.health.latestBody() }
                .onSuccess { (w, h) ->
                    if (w == null && h == null) {
                        ui.value = ui.value.copy(message = "Aucun poids ni taille dans Health Connect.")
                    } else {
                        c.profileStore.update { p -> p.copy(weightKg = w ?: p.weightKg, heightCm = h ?: p.heightCm) }
                        ui.value = ui.value.copy(message = "Profil mis à jour depuis Health Connect.")
                    }
                }
                .onFailure { ui.value = ui.value.copy(message = "Import impossible : ${it.message}") }
        }
    }

    fun updateProfile(f: (UserProfile) -> UserProfile) = c.profileStore.update(f)
}
