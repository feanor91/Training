@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.data.PlanWithSteps
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.ui.components.ProfileChart
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun PlannerScreen(onEditPlan: (Long) -> Unit) {
    val vm = containerViewModel { PlannerViewModel(it) }
    val plans by vm.plans.collectAsState()
    val upcoming by vm.upcoming.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var scheduling by remember { mutableStateOf<PlanWithSteps?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Entraînements") }) },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton({ onEditPlan(0) }) { Icon(Icons.Default.Add, "Nouveau plan") }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Plans") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Calendrier") })
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (tab == 0) {
                    items(plans, key = { it.plan.id }) { p ->
                        PlanCard(p, onEdit = { onEditPlan(p.plan.id) }, onSchedule = { scheduling = p }, onDelete = { vm.deletePlan(p.plan.id) })
                    }
                } else {
                    if (upcoming.isEmpty()) item { Text("Rien de programmé. Utilisez l'icône calendrier d'un plan.") }
                    upcoming.groupBy { it.schedule.epochDay }.forEach { (day, list) ->
                        item(key = "d$day") { Text(formatDay(day), style = MaterialTheme.typography.titleSmall) }
                        items(list, key = { it.schedule.id }) { s ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                        Text(s.plan.plan.name, style = MaterialTheme.typography.titleMedium)
                                        Text(formatClock(s.plan.totalSec) + if (s.schedule.completed) " · ✓ faite" else "")
                                    }
                                    IconButton({ vm.unschedule(s.schedule) }) { Icon(Icons.Default.Delete, "Retirer du calendrier") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    scheduling?.let { plan ->
        ScheduleDialog(plan.plan.name, onDismiss = { scheduling = null }) { date, weeks ->
            vm.schedule(plan.plan.id, date, weeks)
            scheduling = null
        }
    }
}

@Composable
private fun PlanCard(p: PlanWithSteps, onEdit: () -> Unit, onSchedule: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.plan.name, style = MaterialTheme.typography.titleMedium)
            if (p.plan.description.isNotBlank()) Text(p.plan.description, style = MaterialTheme.typography.bodySmall)
            Text("${formatClock(p.totalSec)} · ${"%.1f".format(p.distanceKm)} km · ${p.steps.size} paliers", style = MaterialTheme.typography.bodySmall)
            ProfileChart(p.toWorkoutSteps(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
            Row {
                IconButton(onSchedule) { Icon(Icons.Default.CalendarMonth, "Programmer") }
                IconButton(onEdit) { Icon(Icons.Default.Edit, "Modifier") }
                IconButton(onDelete) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        }
    }
}

@Composable
private fun ScheduleDialog(planName: String, onDismiss: () -> Unit, onConfirm: (LocalDate, Int) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    var weeks by remember { mutableIntStateOf(1) }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                val millis = state.selectedDateMillis ?: return@TextButton
                // Le sélecteur renvoie minuit UTC du jour choisi.
                onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(), weeks)
            }) { Text("Programmer") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } },
    ) {
        Column {
            DatePicker(state, title = { Text(planName, Modifier.padding(start = 24.dp, top = 16.dp)) })
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (weeks == 1) "Une seule fois" else "Chaque semaine pendant $weeks semaines", Modifier.weight(1f))
                IconButton({ weeks = (weeks - 1).coerceAtLeast(1) }) { Text("−") }
                Spacer(Modifier.width(4.dp))
                IconButton({ weeks = (weeks + 1).coerceAtMost(52) }) { Text("+") }
            }
        }
    }
}
