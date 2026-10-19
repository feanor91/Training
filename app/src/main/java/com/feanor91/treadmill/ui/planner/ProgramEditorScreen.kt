@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.feanor91.treadmill.ui.planner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.data.ProgramEntity
import com.feanor91.treadmill.domain.GeneratedSession
import com.feanor91.treadmill.domain.MachineLimits
import com.feanor91.treadmill.domain.ProgramGenerator
import com.feanor91.treadmill.domain.ProgramGoal
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

data class ProgramDraft(
    val goal: ProgramGoal = ProgramGoal.WEIGHT_LOSS,
    val totalDays: Int = 28,
    val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    val start: LocalDate = LocalDate.now(),
    val maxSpeedKmh: Double = 5.0,
    val reminderOn: Boolean = true,
    val reminderMinutes: Int = 20 * 60,
    val name: String = "",
) {
    val displayName get() = name.ifBlank { "${goal.label} · $totalDays jours" }
}

class ProgramEditorViewModel(private val c: AppContainer) : ViewModel() {
    val draft = MutableStateFlow(ProgramDraft())
    val limits: StateFlow<MachineLimits> = c.ftms.limits
    val saved = MutableStateFlow(false)

    fun update(f: (ProgramDraft) -> ProgramDraft) = draft.update(f)

    fun sessions(d: ProgramDraft): List<GeneratedSession> {
        val l = limits.value.takeIf { it.fromDevice }
        return ProgramGenerator.generate(d.goal, d.start, d.totalDays, d.weekdays, d.maxSpeedKmh, l)
    }

    fun create() {
        val d = draft.value
        val sessions = sessions(d)
        if (sessions.isEmpty()) return
        viewModelScope.launch {
            c.repository.createProgram(
                ProgramEntity(
                    name = d.displayName, goal = d.goal.name, startEpochDay = d.start.toEpochDay(), totalDays = d.totalDays,
                    weekdaysMask = d.weekdays.fold(0) { m, day -> m or (1 shl (day.value - 1)) },
                    reminderMinutes = if (d.reminderOn) d.reminderMinutes else -1, maxSpeedKmh = d.maxSpeedKmh,
                ),
                sessions,
            )
            runCatching { c.reminders.reschedule() }
            saved.value = true
        }
    }
}

@Composable
fun ProgramEditorScreen(onClose: () -> Unit) {
    val vm = containerViewModel { ProgramEditorViewModel(it) }
    val d by vm.draft.collectAsState()
    val limits by vm.limits.collectAsState()
    val saved by vm.saved.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(saved) { if (saved) onClose() }

    val sessions = remember(d, limits) { vm.sessions(d) }
    val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.create() }

    fun createWithPermission() {
        val needs = d.reminderOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.create()
    }

    val maxSpeedCap = if (limits.fromDevice) limits.maxSpeedKmh else 20.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouveau programme") },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Objectif", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgramGoal.entries.forEach { g ->
                        FilterChip(d.goal == g, { vm.update { it.copy(goal = g) } }, { Text(g.label) })
                    }
                }
            }
            item {
                OutlinedTextField(
                    d.name, { v -> vm.update { it.copy(name = v) } }, label = { Text("Nom") },
                    placeholder = { Text(d.displayName) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Durée", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.totalDays} jours (${d.totalDays / 7} sem.)", Modifier.weight(1f))
                    OutlinedButton({ vm.update { it.copy(totalDays = (it.totalDays - 7).coerceAtLeast(7)) } }) { Text("−") }
                    Text("  ")
                    Button({ vm.update { it.copy(totalDays = (it.totalDays + 7).coerceAtMost(112)) } }) { Text("+") }
                }
            }
            item {
                Text("Jours d'entraînement", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in d.weekdays,
                            onClick = {
                                vm.update {
                                    val next = if (day in it.weekdays) it.weekdays - day else it.weekdays + day
                                    if (next.isEmpty()) it else it.copy(weekdays = next)
                                }
                            },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Début", style = MaterialTheme.typography.titleMedium)
                        Text(d.start.format(dateFmt))
                    }
                    OutlinedButton({ pickDate = true }) { Text("Changer") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vitesse maximale", style = MaterialTheme.typography.titleMedium)
                        Text("${formatSpeed(d.maxSpeedKmh)} km/h : jamais dépassée par le programme", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton({ vm.update { it.copy(maxSpeedKmh = (it.maxSpeedKmh - 0.5).coerceAtLeast(3.0)) } }) { Text("−") }
                    Text("  ")
                    Button({ vm.update { it.copy(maxSpeedKmh = (it.maxSpeedKmh + 0.5).coerceAtMost(maxSpeedCap)) } }) { Text("+") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rappel les jours d'entraînement", style = MaterialTheme.typography.titleMedium)
                        if (d.reminderOn) Text("À %02d:%02d".format(d.reminderMinutes / 60, d.reminderMinutes % 60))
                    }
                    if (d.reminderOn) TextButton({ pickTime = true }) { Text("Heure") }
                    Switch(d.reminderOn, { on -> vm.update { it.copy(reminderOn = on) } })
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Aperçu : ${sessions.size} séances", style = MaterialTheme.typography.titleMedium)
                        if (sessions.isEmpty()) Text("Aucune séance : choisissez au moins un jour dans la période.")
                        else Text(
                            "De ${formatClock(sessions.first().totalSec)} à ${formatClock(sessions.last().totalSec)} par séance, " +
                                "échauffement 2,5 km/h et retour au calme 2 km/h. Les efforts visent les zones cardio 2-3 " +
                                "et gardent une vitesse fixe sans capteur cardio.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        sessions.forEach { s ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    s.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " +
                                        s.date.format(DateTimeFormatter.ofPattern("dd/MM")) + " · " + s.title,
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                )
                                Text(formatClock(s.totalSec), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                Button(::createWithPermission, Modifier.fillMaxWidth(), enabled = sessions.isNotEmpty()) { Text("Créer le programme") }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = d.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms ->
                        vm.update { it.copy(start = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Annuler") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val state = rememberTimePickerState(d.reminderMinutes / 60, d.reminderMinutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Heure du rappel") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton({
                    vm.update { it.copy(reminderMinutes = state.hour * 60 + state.minute) }
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickTime = false }) { Text("Annuler") } },
        )
    }
}
