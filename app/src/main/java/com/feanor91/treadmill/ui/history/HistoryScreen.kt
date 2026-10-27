@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.domain.Calories
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.data.SessionEntity
import com.feanor91.treadmill.health.ExportableSession
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatKm
import com.feanor91.treadmill.ui.formatSpeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Réessaie l'export d'une séance (agrégats seulement : pas de courbes cardio/vitesse). */
    fun export(s: SessionEntity) = viewModelScope.launch {
        message.value = runCatching {
            val result = c.health.export(
                ExportableSession(
                    s.planName, s.startedAt, s.durationSec, s.distanceM, s.activeKcal, s.avgSpeedKmh, s.steps,
                    restingKcal = (Calories.bmrPerDay(c.profileStore.profile.value) * s.durationSec / 86_400.0).toInt(),
                ),
            )
            if (result.sent) {
                c.repository.markExported(s.id)
                "Séance envoyée à Health Connect." + result.report.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            }
            else "Autorisez d'abord Health Connect dans l'onglet Santé."
        }.getOrElse { "Export impossible : ${it.message}" }
    }

    val sessions: StateFlow<List<SessionEntity>> =
        c.repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) = viewModelScope.launch { c.repository.deleteSession(id) }
}

@Composable
fun HistoryScreen() {
    val vm = containerViewModel { HistoryViewModel(it) }
    val sessions by vm.sessions.collectAsState()
    val message by vm.message.collectAsState()
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Scaffold(topBar = { TopAppBar(title = { Text("Historique") }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Total("Séances", sessions.size.toString(), Modifier.weight(1f))
                            Total("Distance", "${formatKm(sessions.sumOf { it.distanceM })} km", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Total("Temps", formatClock(sessions.sumOf { it.durationSec }), Modifier.weight(1f))
                            Total("Calories actives", "${sessions.sumOf { it.activeKcal }} kcal", Modifier.weight(1f))
                        }
                    }
                }
            }
            message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            if (sessions.isEmpty()) item { Text("Aucune séance enregistrée pour le moment.") }
            items(sessions, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(s.planName, style = MaterialTheme.typography.titleMedium)
                            Text(fmt.format(Date(s.startedAt)), style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${formatClock(s.durationSec)} · ${formatKm(s.distanceM)} km · ${formatSpeed(s.avgSpeedKmh)} km/h moy." +
                                    if (s.avgHr > 0) " · ♥ ${s.avgHr}/${s.maxHr}" else "",
                            )
                            if (s.activeKcal > 0) Text("≈ ${s.activeKcal} kcal actives · ≈ ${s.steps} pas")
                            if (s.exported) Text("✓ Health Connect", style = MaterialTheme.typography.bodySmall)
                            else TextButton({ vm.export(s) }) { Text("Envoyer à Health Connect") }
                        }
                        IconButton({ vm.delete(s.id) }) { Icon(Icons.Default.Delete, "Supprimer") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
