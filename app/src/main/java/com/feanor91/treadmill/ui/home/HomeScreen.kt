@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.data.ScheduledItem
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.domain.ConnectionState
import com.feanor91.treadmill.health.StepsToday
import com.feanor91.treadmill.domain.MachineLimits
import com.feanor91.treadmill.ui.components.ProfileChart
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.components.rememberWorkoutLauncher
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatSpeed

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun HomeScreen() {
    val vm = containerViewModel { HomeViewModel(it) }
    val connection by vm.connection.collectAsState()
    val limits by vm.limits.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val error by vm.ftmsError.collectAsState()
    val strapName by vm.strapName.collectAsState()
    val strapHr by vm.strapHr.collectAsState()
    val watchConnected by vm.watchConnected.collectAsState()
    val watchName by vm.watchName.collectAsState()
    val watchHr by vm.watchHr.collectAsState()
    val stepsToday by vm.steps.collectAsState()
    val today by vm.today.collectAsState()
    val devices by vm.devices.collectAsState()
    val scanTarget by vm.scanTarget.collectAsState()
    val scanError by vm.scanError.collectAsState()

    var pendingScan by remember { mutableStateOf<ScanTarget?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) pendingScan?.let(vm::startScan)
        pendingScan = null
    }
    fun requestScan(target: ScanTarget) {
        pendingScan = target
        val perms = buildList {
            addAll(blePermissions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val launch = rememberWorkoutLauncher()
    val ready = connection == ConnectionState.Ready

    Scaffold(topBar = { TopAppBar(title = { Text("Aujourd'hui") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                TreadmillCard(
                    state = connection, name = deviceName, limits = limits, speed = metrics.speedKmh,
                    error = error,
                    onScan = { requestScan(ScanTarget.Treadmill) },
                    onDisconnect = vm::disconnectTreadmill,
                    onDismissError = vm::dismissError,
                )
            }
            item {
                StrapCard(
                    name = strapName, hr = strapHr ?: metrics.heartRate,
                    onScan = { requestScan(ScanTarget.HeartRate) }, onDisconnect = vm::disconnectStrap,
                )
            }
            item { WatchCard(watchConnected, watchName, watchHr) }
            if (!stepsToday.unavailable) item { StepsCard(stepsToday) }
            item { Text("Séances du jour", style = MaterialTheme.typography.titleMedium) }
            if (today.isEmpty()) {
                item {
                    Text(
                        "Aucune séance programmée aujourd'hui. Ajoutez-en depuis l'onglet Plans.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(today, key = { it.schedule.id }) { item ->
                TodayCard(item) { launch(item.plan.plan.name, item.plan.toWorkoutSteps(), item.schedule.id) }
            }
            item {
                OutlinedButton(
                    onClick = { launch("Course libre", vm.freeRunSteps(), null) }, modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Course libre")
                }
                if (!ready) {
                    Text(
                        "Tapis non connecté : la séance démarre en mode simulé (chrono, vitesse et distance suivent vos consignes).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    scanTarget?.let { target ->
        DevicePickerDialog(
            title = if (target == ScanTarget.Treadmill) "Tapis (FTMS)" else "Capteur cardiaque",
            devices = devices, error = scanError,
            onPick = vm::connect, onDismiss = vm::stopScan,
        )
    }
}

@Composable
private fun TreadmillCard(
    state: ConnectionState, name: String?, limits: MachineLimits, speed: Double?, error: String?,
    onScan: () -> Unit, onDisconnect: () -> Unit, onDismissError: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state == ConnectionState.Ready) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(name ?: "Tapis de course", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state) {
                            ConnectionState.Ready -> "Connecté" + (speed?.let { " · ${formatSpeed(it)} km/h" } ?: "")
                            ConnectionState.Connecting -> "Connexion…"
                            else -> "Non connecté"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state == ConnectionState.Connecting) CircularProgressIndicator(Modifier.width(24.dp))
            }
            if (state == ConnectionState.Ready) {
                Text(
                    "Vitesse ${formatSpeed(limits.minSpeedKmh)} – ${formatSpeed(limits.maxSpeedKmh)} km/h (pas ${formatSpeed(limits.speedStepKmh)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (limits.supportsIncline) {
                        "Inclinaison ${formatSpeed(limits.minInclinePct)} – ${formatSpeed(limits.maxInclinePct)} % (pas ${formatSpeed(limits.inclineStepPct)})"
                    } else "Inclinaison non disponible",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!limits.fromDevice) {
                    Text("Limites non fournies par le tapis : valeurs par défaut.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onDisconnect) { Text("Déconnecter") }
            } else {
                Button(onScan, enabled = state != ConnectionState.Connecting) { Text("Rechercher un tapis") }
            }
            if (error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onDismissError) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun StrapCard(name: String?, hr: Int?, onScan: () -> Unit, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name ?: "Capteur cardiaque", style = MaterialTheme.typography.titleSmall)
                Text(hr?.let { "$it bpm" } ?: "Optionnel · sinon cardio du tapis", style = MaterialTheme.typography.bodySmall)
            }
            if (name == null) TextButton(onScan) { Text("Associer") } else TextButton(onDisconnect) { Text("Retirer") }
        }
    }
}

@Composable
private fun StepsCard(s: StepsToday) {
    val fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("👣", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column {
                when {
                    s.permissionMissing -> {
                        Text("Pas du jour", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Autorisez la lecture des pas dans l'onglet Santé.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    s.count == null -> Text("Pas du jour : lecture en cours…", style = MaterialTheme.typography.titleSmall)
                    else -> {
                        Text(
                            "%,d pas aujourd'hui".format(s.count),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Health Connect (toutes vos applis) · mis à jour à ${fmt.format(java.util.Date(s.readAtMs))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchCard(connected: Boolean, name: String?, hr: Int?) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Watch, null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(if (connected) (name ?: "Montre") else "Montre", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        hr != null -> "$hr bpm (mesure en direct)"
                        connected -> "Connectée · ouvrez l'appli sur la montre pendant la séance"
                        else -> "Non détectée · installez l'appli compagnon sur la montre"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TodayCard(item: ScheduledItem, onStart: () -> Unit) {
    val steps = item.plan.toWorkoutSteps()
    Card(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.plan.plan.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatClock(item.plan.totalSec)} · ${"%.1f".format(item.plan.distanceKm)} km" +
                            if (item.schedule.completed) " · ✓ faite" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onStart) { Text("Démarrer") }
            }
            ProfileChart(steps, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun DevicePickerDialog(
    title: String, devices: List<com.feanor91.treadmill.domain.BleDevice>, error: String?,
    onPick: (com.feanor91.treadmill.domain.BleDevice) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                else if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Recherche en cours… Allumez le tapis.")
                    }
                }
                devices.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text("${d.address} · ${d.rssi} dBm") },
                        modifier = Modifier.clickable { onPick(d) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Annuler") } },
    )
}
