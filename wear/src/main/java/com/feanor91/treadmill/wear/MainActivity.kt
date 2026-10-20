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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { WatchScreen() } }
        noteOpenRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteOpenRequest(intent)
    }

    private fun noteOpenRequest(intent: Intent?) {
        if (intent?.data?.scheme == "treadmill") WatchState.openRequests.value += 1
    }
}

private fun clock(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

@Composable
private fun WatchScreen() {
    val context = LocalContext.current
    val hr by WatchState.heartRate.collectAsState()
    val measuring by WatchState.measuring.collectAsState()
    val phone by WatchState.phone.collectAsState()
    val error by WatchState.error.collectAsState()

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
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (phone.countdown > 0) {
            Text("Départ dans", fontSize = 14.sp, color = Color.Gray)
            Text("${phone.countdown}", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color(0xFF5FD3B5))
        } else {
            Text(
                "♥ ${hr ?: "--"}",
                fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B),
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
        }
    }
}
