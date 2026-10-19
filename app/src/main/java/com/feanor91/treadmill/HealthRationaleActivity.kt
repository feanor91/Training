package com.feanor91.treadmill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.ui.theme.TreadmillTheme

/** Explique à quoi servent les accès Health Connect (affiché par Health Connect lors de la demande). */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TreadmillTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Données de santé", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Treadmill FTMS enregistre vos séances (durée, distance, vitesse, rythme cardiaque, calories actives) " +
                                "dans Health Connect, et lit le poids, la taille, les apports caloriques et les dépenses fournis par " +
                                "vos autres applications pour calculer votre bilan et votre déficit calorique. " +
                                "Ces données restent sur votre téléphone ; vous pouvez retirer les accès à tout moment dans Health Connect.",
                        )
                        Button(onClick = { finish() }) { Text("Compris") }
                    }
                }
            }
        }
    }
}
