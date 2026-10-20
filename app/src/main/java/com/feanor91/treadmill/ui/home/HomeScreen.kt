@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.data.PlanWithSteps
import com.feanor91.treadmill.data.ScheduledItem
import com.feanor91.treadmill.data.toWorkoutSteps
import com.feanor91.treadmill.domain.ConnectionState
import com.feanor91.treadmill.health.StepsToday
import com.feanor91.treadmill.ui.components.ProfileChart
import com.feanor91.treadmill.ui.components.containerViewModel
import com.feanor91.treadmill.ui.components.rememberWorkoutLauncher
import com.feanor91.treadmill.ui.formatClock
import com.feanor91.treadmill.ui.theme.HeroBrush
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { HomeViewModel(it) }
    val connection by vm.connection.collectAsState()
    val strapName by vm.strapName.collectAsState()
    val watchConnected by vm.watchConnected.collectAsState()
    val stepsToday by vm.steps.collectAsState()
    val today by vm.today.collectAsState()
    val favorites by vm.favorites.collectAsState()

    val launch = rememberWorkoutLauncher()
    val ready = connection == ConnectionState.Ready
    val hero = today.firstOrNull { !it.schedule.completed }
    val others = today.filter { it !== hero }

    Scaffold { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                Column {
                    Text("Aujourd'hui", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HeroCard(
                    session = hero,
                    onStart = {
                        if (hero != null) launch(hero.plan.plan.name, hero.plan.toWorkoutSteps(), hero.schedule.id)
                        else launch("Course libre", vm.freeRunSteps(), null)
                    },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Tapis", ready, onOpenSettings)
                    StatusPill("Cardio", strapName != null, onOpenSettings)
                    StatusPill("Montre", watchConnected, onOpenSettings)
                }
            }
            if (!ready) {
                item {
                    Text(
                        "Tapis non connecté : la séance démarre en mode simulé (chrono, vitesse et distance suivent vos consignes).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!stepsToday.unavailable) item { StepsCard(stepsToday) }

            if (others.isNotEmpty()) {
                item { Text("Autres séances du jour", style = MaterialTheme.typography.titleMedium) }
                items(others, key = { it.schedule.id }) { item ->
                    SessionRow(item.plan.plan.name, "${formatClock(item.plan.totalSec)}" + if (item.schedule.completed) " · ✓ faite" else "") {
                        launch(item.plan.plan.name, item.plan.toWorkoutSteps(), item.schedule.id)
                    }
                }
            }
            if (favorites.isNotEmpty()) {
                item { Text("Mes favoris", style = MaterialTheme.typography.titleMedium) }
                items(favorites, key = { "fav${it.plan.id}" }) { p ->
                    SessionRow(p.plan.name, "${formatClock(p.totalSec)} · ${"%.1f".format(p.distanceKm)} km") {
                        launch(p.plan.name, p.toWorkoutSteps(), null)
                    }
                }
            }
            if (hero != null) {
                item {
                    OutlinedButton(
                        onClick = { launch("Course libre", vm.freeRunSteps(), null) }, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Course libre")
                    }
                }
            }
        }
    }
}

/** Grande carte en dégradé : la séance du jour (ou une invitation à courir). */
@Composable
private fun HeroCard(session: ScheduledItem?, onStart: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(HeroBrush).padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session != null) {
                Text("SÉANCE DU JOUR", style = MaterialTheme.typography.labelMedium, color = Color(0xB3FFFFFF), fontWeight = FontWeight.Bold)
                Text(session.plan.plan.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "${formatClock(session.plan.totalSec)} · ${"%.1f".format(session.plan.distanceKm)} km · ${session.plan.steps.size} paliers",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF),
                )
                ProfileChart(session.plan.toWorkoutSteps(), Color.White, Color(0xFFFFE08A), height = 52.dp)
            } else {
                Text("PRÊT À COURIR ?", style = MaterialTheme.typography.labelMedium, color = Color(0xB3FFFFFF), fontWeight = FontWeight.Bold)
                Text("Aucune séance prévue", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Lancez une course libre, un de vos favoris, ou programmez des séances depuis l'onglet Plans.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF),
                )
            }
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF065F46)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (session != null) "Démarrer" else "Course libre", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(if (ok) Color(0xFF2EBD85) else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SessionRow(title: String, subtitle: String, onStart: () -> Unit) {
    Card(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onStart) { Icon(Icons.Default.PlayArrow, "Lancer") }
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

