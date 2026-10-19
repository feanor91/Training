@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.domain.WorkoutStep
import com.feanor91.treadmill.ui.components.ProfileChart
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatIncline
import com.feanor91.treadmill.ui.formatSpeed

@Composable
fun PlanEditorScreen(planId: Long, onClose: () -> Unit) {
    val vm = containerViewModel(key = "plan$planId") { PlanEditorViewModel(it, planId) }
    val draft by vm.draft.collectAsState()
    val limits by vm.limits.collectAsState()
    val saved by vm.saved.collectAsState()
    LaunchedEffect(saved) { if (saved) onClose() }

    val speedStep = if (limits.fromDevice) limits.speedStepKmh.coerceAtLeast(0.1) else 0.1
    val inclineStep = if (limits.fromDevice) limits.inclineStepPct.coerceAtLeast(0.1) else 0.5

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (planId == 0L) "Nouveau plan" else "Modifier le plan") },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = { TextButton(vm::save, enabled = draft.name.isNotBlank() && draft.steps.isNotEmpty()) { Text("Enregistrer") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(draft.name, vm::setName, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(draft.description, vm::setDescription, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                val steps = draft.steps.map { WorkoutStep(it.durationSec, it.speedKmh, it.inclinePct, it.label) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProfileChart(steps, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, height = 72.dp)
                    Text(
                        "Total ${formatClock(draft.steps.sumOf { it.durationSec })} · " +
                            "${"%.2f".format(draft.steps.sumOf { it.speedKmh * it.durationSec / 3600.0 })} km" +
                            if (limits.fromDevice) " · vitesse ${formatSpeed(limits.minSpeedKmh)}–${formatSpeed(limits.maxSpeedKmh)} km/h (tapis connecté)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            itemsIndexed(draft.steps) { i, s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                s.label, { v -> vm.updateStep(i) { it.copy(label = v) } },
                                label = { Text("Palier ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            IconButton({ vm.moveStep(i, -1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Monter") }
                            IconButton({ vm.moveStep(i, 1) }, enabled = i < draft.steps.lastIndex) { Icon(Icons.Default.ArrowDownward, "Descendre") }
                        }
                        Stepper("Durée", formatClock(s.durationSec),
                            onMinus = { vm.updateStep(i) { it.copy(durationSec = (it.durationSec - 30).coerceAtLeast(30)) } },
                            onPlus = { vm.updateStep(i) { it.copy(durationSec = it.durationSec + 30) } })
                        Stepper("Vitesse", "${formatSpeed(s.speedKmh)} km/h",
                            onMinus = { vm.updateStep(i) { it.copy(speedKmh = limits.clampSpeed(it.speedKmh - speedStep)) } },
                            onPlus = { vm.updateStep(i) { it.copy(speedKmh = limits.clampSpeed(it.speedKmh + speedStep)) } })
                        Stepper("Pente", "${formatIncline(s.inclinePct)} %",
                            onMinus = { vm.updateStep(i) { it.copy(inclinePct = limits.clampIncline(it.inclinePct - inclineStep)) } },
                            onPlus = { vm.updateStep(i) { it.copy(inclinePct = limits.clampIncline(it.inclinePct + inclineStep)) } })
                        Row {
                            IconButton({ vm.duplicateStep(i) }) { Icon(Icons.Default.ContentCopy, "Dupliquer") }
                            IconButton({ vm.removeStep(i) }, enabled = draft.steps.size > 1) { Icon(Icons.Default.Delete, "Supprimer") }
                        }
                    }
                }
            }
            item { OutlinedButton(vm::addStep, Modifier.fillMaxWidth()) { Text("Ajouter un palier") } }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onMinus) { Text("−") }
        Text(value, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        Button(onPlus) { Text("+") }
    }
}
