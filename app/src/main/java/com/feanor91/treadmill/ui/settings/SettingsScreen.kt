@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feanor91.treadmill.AppContainer
import com.feanor91.treadmill.domain.BleDevice
import com.feanor91.treadmill.domain.ConnectionState
import com.feanor91.treadmill.domain.MachineLimits
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.formatSpeed
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class ScanTarget { Treadmill, HeartRate }

/** Appareils : tapis (FTMS), capteur cardiaque Bluetooth, montre. */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
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
    val autoOpenWatch = c.profileStore.autoOpenWatch
    fun setAutoOpenWatch(v: Boolean) = c.profileStore.setAutoOpenWatch(v)

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
}

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }


@Composable
fun SettingsScreen(onOpenHealth: () -> Unit) {
    val vm = containerViewModel { SettingsViewModel(it) }
    val context = LocalContext.current
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
    val autoOpen by vm.autoOpenWatch.collectAsState()
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

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item { SectionTitle("Tapis de course") }
            item {
                TreadmillCard(
                    state = connection, name = deviceName, limits = limits, speed = metrics.speedKmh, error = error,
                    onScan = { requestScan(ScanTarget.Treadmill) },
                    onDisconnect = vm::disconnectTreadmill,
                    onDismissError = vm::dismissError,
                )
            }
            item { SectionTitle("Capteur cardiaque") }
            item {
                StrapCard(
                    name = strapName, hr = strapHr ?: metrics.heartRate,
                    onScan = { requestScan(ScanTarget.HeartRate) }, onDisconnect = vm::disconnectStrap,
                )
            }
            item { SectionTitle("Montre") }
            item { WatchCard(watchConnected, watchName, watchHr, autoOpen, vm::setAutoOpenWatch) }
            item { SectionTitle("Santé") }
            item {
                Card(onClick = onOpenHealth, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profil, zones cardio et Health Connect", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Poids, taille, FC max, correction des calories et autorisations Health Connect se règlent dans l'onglet Santé.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item { SectionTitle("À propos") }
            item { Text("Treadmill FTMS · version $version", style = MaterialTheme.typography.bodySmall) }
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
private fun WatchCard(connected: Boolean, name: String?, hr: Int?, autoOpen: Boolean, onAutoOpen: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ouvrir l'appli de la montre au début d'une séance", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(autoOpen, onAutoOpen)
        }
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
