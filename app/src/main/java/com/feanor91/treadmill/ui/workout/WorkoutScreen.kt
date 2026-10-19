package com.feanor91.treadmill.ui.workout

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feanor91.treadmill.domain.WorkoutState
import com.feanor91.treadmill.ui.components.ProfileChart
import com.feanor91.treadmill.ui.components.appContainer
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.formatIncline
import com.feanor91.treadmill.ui.formatKm
import com.feanor91.treadmill.ui.formatPace
import com.feanor91.treadmill.ui.formatSpeed
import com.feanor91.treadmill.ui.theme.WorkoutColors

/** Écran de séance épuré : uniquement ce qui compte pendant l'effort, gros et lisible à distance. */
@Composable
fun WorkoutScreen() {
    val engine = appContainer().engine
    val ui by engine.ui.collectAsState()
    val exportStatus by appContainer().exportStatus.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }

    KeepScreenOn()
    BackHandler { confirmStop = true }

    Box(Modifier.fillMaxSize().background(WorkoutColors.Background)) {
    Column(
        Modifier.fillMaxSize().background(WorkoutColors.Background).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // En-tête : palier courant + temps
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.currentStep?.label?.ifBlank { null } ?: ui.planName,
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (ui.state == WorkoutState.Paused) Text("PAUSE", color = WorkoutColors.Incline, fontWeight = FontWeight.Bold)
            }
            ui.nextStep?.let {
                Text(
                    "Ensuite : ${formatSpeed(it.speedKmh)} km/h · ${formatIncline(it.inclinePct)} %",
                    color = WorkoutColors.Dim, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(ui.elapsedSec), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (ui.isUnlimited) "${formatKm(ui.distanceM)} km"
                    else "-" + formatClock((ui.totalSec - ui.elapsedSec).coerceAtLeast(0)),
                    color = WorkoutColors.Dim, fontSize = 44.sp,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🔥 ${ui.kcal} kcal", color = WorkoutColors.Incline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("👣 %,d pas".format(ui.stepCount).replace(',', ' '), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (!ui.isUnlimited) Text("${formatKm(ui.distanceM)} km", color = WorkoutColors.Dim, fontSize = 22.sp)
            }
            if (!ui.isUnlimited) {
                LinearProgressIndicator(
                    progress = { (ui.elapsedSec.toFloat() / ui.totalSec).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = WorkoutColors.Speed, trackColor = Color(0xFF2A2A2A),
                )
            }
        }

        // Cœur : vitesse (adjustable), pente (adjustable), cardio
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigMetric(
                label = "VITESSE km/h", value = formatSpeed(if (ui.speedKmh > 0 || ui.state == WorkoutState.Paused) ui.speedKmh else ui.targetSpeed),
                sub = ui.currentStep?.targetZone?.let { z -> "Auto · zone $z · cible ${formatSpeed(ui.targetSpeed)}" }
                    ?: "Allure ${formatPace(ui.speedKmh)} /km · cible ${formatSpeed(ui.targetSpeed)}",
                color = WorkoutColors.Speed,
                onMinus = { engine.adjustSpeed(-0.5) }, onPlus = { engine.adjustSpeed(0.5) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigMetric(
                    label = "PENTE %", value = formatIncline(ui.inclinePct), sub = "cible ${formatIncline(ui.targetIncline)}",
                    color = WorkoutColors.Incline, valueSize = 56, modifier = Modifier.weight(1f),
                    onMinus = { engine.adjustIncline(-0.5) }, onPlus = { engine.adjustIncline(0.5) },
                )
                BigMetric(
                    label = "♥ BPM", value = ui.heartRate?.toString() ?: "--",
                    sub = if (ui.heartRate != null && ui.hrZone in 1..5) "Zone ${ui.hrZone}" + (ui.currentStep?.targetZone?.let { " · cible $it" } ?: "") else "",
                    color = if (ui.heartRate != null) zoneColor(ui.hrZone) else WorkoutColors.Heart,
                    valueSize = 56, modifier = Modifier.weight(1f),
                )
            }
        }

        // Profil + commandes
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (ui.steps.size > 1) {
                ProfileChart(ui.steps, WorkoutColors.Speed, WorkoutColors.Incline, height = 48.dp, highlight = ui.stepIndex)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ confirmStop = true }, Modifier.size(64.dp)) { Icon(Icons.Default.Stop, "Arrêter") }
                FilledIconButton(
                    { if (ui.state == WorkoutState.Paused) engine.resume() else engine.pause() },
                    Modifier.size(88.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = WorkoutColors.Speed, contentColor = Color.Black),
                ) {
                    Icon(
                        if (ui.state == WorkoutState.Paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        "Pause / reprise", Modifier.size(44.dp),
                    )
                }
                FilledTonalIconButton({ engine.skipStep() }, Modifier.size(64.dp), enabled = ui.steps.size > 1) {
                    Icon(Icons.Default.SkipNext, "Palier suivant")
                }
            }
        }
    }

    if (ui.countdownSec > 0) {
        Column(
            Modifier.fillMaxSize().background(WorkoutColors.Background).statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Le tapis démarre dans", color = WorkoutColors.Dim, fontSize = 20.sp)
            Text(ui.countdownSec.toString(), color = WorkoutColors.Speed, fontSize = 180.sp, fontWeight = FontWeight.Bold)
            Text(ui.planName, color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(32.dp))
            TextButton({ engine.stop() }) { Text("Annuler", color = WorkoutColors.Dim) }
        }
    }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Terminer la séance ?") },
            confirmButton = { TextButton({ confirmStop = false; engine.stop() }) { Text("Terminer") } },
            dismissButton = { TextButton({ confirmStop = false }) { Text("Continuer") } },
        )
    }

    ui.summary?.takeIf { ui.state == WorkoutState.Finished }?.let { s ->
        AlertDialog(
            onDismissRequest = engine::dismiss,
            title = { Text(if (s.completed) "Séance terminée 🎉" else "Séance arrêtée") },
            text = {
                Column {
                Text(
                    "Durée ${formatClock(s.durationSec)}\nDistance ${formatKm(s.distanceM)} km\n" +
                        "Vitesse moyenne ${formatSpeed(s.avgSpeedKmh)} km/h\nDépense active ≈ ${s.activeKcal} kcal\nPas estimés ≈ ${s.steps}" +
                        (if (s.zoneSeconds.drop(1).sum() > 0) "\nTemps par zone : " + (1..5).joinToString(" · ") { z -> "Z$z ${formatClock(s.zoneSeconds[z])}" } else "") +
                        if (s.avgHr > 0) "\nCardio moyen ${s.avgHr} · max ${s.maxHr} bpm" else "",
                    textAlign = TextAlign.Start,
                )
                exportStatus?.let { Text("\n$it", fontWeight = FontWeight.Medium) }
                }
            },
            confirmButton = { TextButton(engine::dismiss) { Text("OK") } },
        )
    }
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> Color(0xFF6EA8FE)
    2 -> Color(0xFF5FD3B5)
    3 -> Color(0xFFFFD43B)
    4 -> Color(0xFFFFA94D)
    5 -> Color(0xFFFF6B6B)
    else -> Color(0xFF9AA0A6)
}

@Composable
private fun BigMetric(
    label: String,
    value: String,
    sub: String,
    color: Color,
    modifier: Modifier = Modifier,
    valueSize: Int = 110,
    onMinus: (() -> Unit)? = null,
    onPlus: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = WorkoutColors.Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                if (onMinus != null) FilledTonalIconButton(onMinus, Modifier.size(48.dp)) { Icon(Icons.Default.Remove, "Moins") }
                else Spacer(Modifier.size(1.dp))
                Text(value, color = color, fontSize = valueSize.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (onPlus != null) FilledTonalIconButton(onPlus, Modifier.size(48.dp)) { Icon(Icons.Default.Add, "Plus") }
                else Spacer(Modifier.size(1.dp))
            }
            if (sub.isNotEmpty()) Text(sub, color = WorkoutColors.Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
