package com.feanor91.treadmill.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchScreen(onKeepScreenOn = ::applyKeepScreenOn) } }
        noteOpenRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteOpenRequest(intent)
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun noteOpenRequest(intent: Intent?) {
        if (intent?.data?.scheme == "treadmill") WatchState.openRequests.value += 1
    }
}

private fun clock(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

@Composable
private fun WatchScreen(onKeepScreenOn: (Boolean) -> Unit) {
    val context = LocalContext.current
    val hr by WatchState.heartRate.collectAsState()
    val measuring by WatchState.measuring.collectAsState()
    val phone by WatchState.phone.collectAsState()
    val error by WatchState.error.collectAsState()

    val scope = rememberCoroutineScope()
    val canAdjust = phone.running && phone.countdown == 0
    val adjust: (Double) -> Unit = { delta ->
        scope.launch { HeartRateService.sendToPhone(context, Protocol.PATH_SPEED_DELTA, delta.toString()) }
    }
    var keepOn by remember { mutableStateOf(WatchSettings.keepScreenOn(context)) }
    // L'écran ne reste allumé que pendant une séance (le cardio n'est pas transmis écran éteint).
    val sessionActive = phone.running || measuring
    LaunchedEffect(keepOn, sessionActive) { onKeepScreenOn(keepOn && sessionActive) }

    val permissions = buildList {
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BODY_SENSORS] == true) HeartRateService.start(context)
    }

    // Ouverture automatique par le téléphone : on démarre la mesure tout de suite.
    val openRequests by WatchState.openRequests.collectAsState()
    LaunchedEffect(openRequests) {
        if (openRequests > 0 && !measuring) launcher.launch(permissions)
    }

    // Séance lancée depuis le téléphone : on démarre la mesure dès l'ouverture de l'appli.
    LaunchedEffect(phone.running) {
        if (phone.running && !measuring) launcher.launch(permissions)
    }

    Column(
        Modifier.fillMaxSize()
            // Arrêt d'urgence : toucher n'importe où hors des boutons pendant une séance.
            .pointerInput(phone.running) {
                detectTapGestures {
                    if (phone.running) scope.launch { HeartRateService.sendToPhone(context, Protocol.PATH_EMERGENCY_STOP, "1") }
                }
            }
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (canAdjust) SpeedChip("+ 0,5 km/h") { adjust(0.5) }
        if (phone.countdown > 0) {
            Text("Départ dans", fontSize = 14.sp, color = Color.Gray)
            Text("${phone.countdown}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5FD3B5))
        } else {
            Text(
                "♥ ${hr ?: "--"}",
                fontSize = if (canAdjust) 32.sp else 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B),
            )
            if (phone.running) {
                Text(
                    "${clock(phone.elapsedSec)}" + if (phone.remainingSec >= 0) "  -${clock(phone.remainingSec)}" else "",
                    fontSize = 18.sp,
                )
                Text(
                    "%.1f km/h · %.1f %%".format(phone.speedKmh, phone.inclinePct),
                    fontSize = 14.sp, color = Color(0xFF5FD3B5),
                )
                if (phone.step.isNotBlank()) Text(phone.step, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                Text("${phone.kcal} kcal · ${phone.steps} pas", fontSize = 12.sp, color = Color.Gray)
            } else {
                Text(
                    if (measuring) "Mesure en cours" else "Lancez une séance sur le téléphone",
                    fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center,
                )
            }
        }
        if (canAdjust) SpeedChip("− 0,5 km/h") { adjust(-0.5) }
        error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFFA94D), textAlign = TextAlign.Center) }
        Spacer(Modifier.height(6.dp))
        // Mesure manuelle (test du capteur) : inutile pendant une séance lancée depuis le téléphone.
        if (!phone.running) {
            Chip(
                onClick = { if (measuring) HeartRateService.stop(context) else launcher.launch(permissions) },
                label = {
                    Text(
                        if (measuring) "Arrêter la mesure" else "Tester le cardio",
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, fontSize = 13.sp,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.78f).height(38.dp),
            )
            Spacer(Modifier.height(4.dp))
            Chip(
                onClick = {
                    keepOn = !keepOn
                    WatchSettings.setKeepScreenOn(context, keepOn)
                    onKeepScreenOn(keepOn)
                },
                label = {
                    Text(
                        if (keepOn) "Séance : écran allumé" else "Séance : écran libre",
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, fontSize = 11.sp,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.78f).height(34.dp),
            )
        }
    }
}

@Composable
private fun SpeedChip(text: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, fontSize = 13.sp) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(0.6f).height(30.dp),
    )
}
