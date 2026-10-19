@file:OptIn(ExperimentalMaterial3Api::class)

package com.feanor91.treadmill.ui.health

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.feanor91.treadmill.domain.Sex
import com.feanor91.treadmill.health.DayBalance
import com.feanor91.treadmill.health.HcAvailability
import com.feanor91.treadmill.ui.components.containerViewModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val Good = Color(0xFF2E9E6B)
private val Bad = Color(0xFFD9822B)

@Composable
fun HealthScreen() {
    val vm = containerViewModel { HealthViewModel(it) }
    val ui by vm.ui.collectAsState()
    val profile by vm.profile.collectAsState()
    val context = LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { vm.refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("Santé & calories") }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                        when {
                            ui.availability == HcAvailability.Unsupported ->
                                Text("Non disponible sur cet appareil (Android 9 ou plus requis).")
                            ui.availability == HcAvailability.NeedsInstallOrUpdate -> {
                                Text("Health Connect doit être installé ou mis à jour.")
                                Button({
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")),
                                    )
                                }) { Text("Ouvrir le Play Store") }
                            }
                            !ui.permissionsGranted -> {
                                Text(
                                    "Autorisez l'accès pour envoyer vos séances (distance, vitesse, cardio, calories) " +
                                        "et lire les calories de vos autres applications.",
                                )
                                Button({ permissionLauncher.launch(vm.permissions) }) { Text("Autoriser Health Connect") }
                            }
                            else -> Text("Connecté : vos séances sont envoyées automatiquement.", color = Good)
                        }
                        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (ui.permissionsGranted && ui.days.isNotEmpty()) {
                item { TodayCard(ui.days.first()) }
                item { WeekCard(ui.days) }
            } else if (ui.loading) {
                item { Text("Lecture de Health Connect…") }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profil (calcul des calories)", style = MaterialTheme.typography.titleMedium)
                        ProfileRow("Poids", "${"%.1f".format(profile.weightKg)} kg",
                            { vm.updateProfile { it.copy(weightKg = it.weightKg - 0.5) } },
                            { vm.updateProfile { it.copy(weightKg = it.weightKg + 0.5) } })
                        ProfileRow("Taille", "${profile.heightCm.roundToInt()} cm",
                            { vm.updateProfile { it.copy(heightCm = it.heightCm - 1) } },
                            { vm.updateProfile { it.copy(heightCm = it.heightCm + 1) } })
                        ProfileRow("Âge", "${profile.age} ans",
                            { vm.updateProfile { it.copy(age = it.age - 1) } },
                            { vm.updateProfile { it.copy(age = it.age + 1) } })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Sexe", Modifier.weight(1f))
                            FilterChip(profile.sex == Sex.Male, { vm.updateProfile { it.copy(sex = Sex.Male) } }, { Text("Homme") })
                            FilterChip(profile.sex == Sex.Female, { vm.updateProfile { it.copy(sex = Sex.Female) } }, { Text("Femme") })
                        }
                        if (ui.permissionsGranted) {
                            OutlinedButton(vm::importBody) { Text("Importer poids et taille depuis Health Connect") }
                        }
                        Text(
                            "Dépense active estimée avec les équations ACSM (marche / course sur tapis) ; " +
                                "repos : formule de Mifflin-St Jeor. Ce sont des estimations.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCard(d: DayBalance) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Aujourd'hui", style = MaterialTheme.typography.titleMedium)
            Line("Apports (nutrition)", d.intakeKcal?.let { "${it.roundToInt()} kcal" } ?: "—")
            Line("Métabolisme de repos écoulé", "${d.basalKcal.roundToInt()} kcal")
            Line("Activité (toutes applis)", "${d.activeKcal.roundToInt()} kcal")
            HorizontalDivider()
            Line("Dépenses totales", "${d.burnedKcal.roundToInt()} kcal", bold = true)
            val deficit = d.deficitKcal
            if (deficit == null) {
                Text(
                    "Aucun apport reçu : activez le partage Health Connect dans votre application de suivi alimentaire.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    if (deficit >= 0) "Déficit : ${deficit.roundToInt()} kcal" else "Excédent : ${(-deficit).roundToInt()} kcal",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = if (deficit >= 0) Good else Bad,
                )
                Text(
                    "Bilan à l'heure actuelle (le repos est proratisé sur la journée).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WeekCard(days: List<DayBalance>) {
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val withIntake = days.filter { it.deficitKcal != null }
    val total = withIntake.sumOf { it.deficitKcal ?: 0.0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("7 derniers jours", style = MaterialTheme.typography.titleMedium)
            days.forEach { d ->
                val deficit = d.deficitKcal
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.date.format(fmt))
                    Text(
                        if (deficit == null) "pas de nutrition"
                        else (if (deficit >= 0) "−" else "+") + "${kotlin.math.abs(deficit).roundToInt()} kcal",
                        color = if (deficit == null) MaterialTheme.colorScheme.onSurfaceVariant else if (deficit >= 0) Good else Bad,
                    )
                }
            }
            if (withIntake.isNotEmpty()) {
                HorizontalDivider()
                Line(
                    "Cumul (${withIntake.size} j avec données)",
                    (if (total >= 0) "déficit " else "excédent ") + "${kotlin.math.abs(total).roundToInt()} kcal",
                    bold = true,
                )
                Text(
                    "≈ ${"%.2f".format(kotlin.math.abs(total) / 7700.0)} kg de masse grasse (équivalence théorique de 7 700 kcal/kg).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else null)
        Text(value, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onMinus) { Text("−") }
        Text(value, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        Button(onPlus) { Text("+") }
    }
}
