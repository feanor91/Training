package com.feanor91.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.domain.WorkoutStep

/**
 * Profil de séance : largeur = durée, hauteur = vitesse, teinte = pente.
 *
 * En séance, [activeStep] et [activeFraction] (0..1 dans le palier) rendent le graphique progressif :
 * la partie déjà courue est en couleur, la suite est atténuée, et un repère vertical marque l'instant présent.
 */
@Composable
fun ProfileChart(
    steps: List<WorkoutStep>,
    base: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    activeStep: Int = -1,
    activeFraction: Float = 0f,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (steps.isEmpty()) return@Canvas
        val weights = steps.map { (if (it.durationSec > 0) it.durationSec else 60).toFloat() }
        val total = weights.sum()
        val maxSpeed = (steps.maxOf { it.speedKmh }).coerceAtLeast(1.0).toFloat()
        val maxIncline = (steps.maxOf { it.inclinePct }).coerceAtLeast(1.0).toFloat()
        val gap = 1.5f

        fun drawBars(alpha: Float) {
            var x = 0f
            steps.forEachIndexed { i, s ->
                val w = size.width * weights[i] / total
                val h = size.height * (s.speedKmh.toFloat() / maxSpeed).coerceIn(0.08f, 1f)
                val mix = (s.inclinePct.toFloat() / maxIncline).coerceIn(0f, 1f)
                drawRect(lerp(base, accent, mix).copy(alpha = alpha), Offset(x, size.height - h), Size((w - gap).coerceAtLeast(1f), h))
                x += w
            }
        }

        if (activeStep < 0) {
            drawBars(1f)
            return@Canvas
        }
        // Position du repère : début du palier courant + part de ce palier déjà écoulée.
        val before = weights.take(activeStep.coerceIn(0, steps.size)).sum()
        val current = weights.getOrElse(activeStep) { 0f }
        val xNow = size.width * (before + current * activeFraction.coerceIn(0f, 1f)) / total

        drawBars(0.28f)
        clipRect(left = 0f, top = 0f, right = xNow, bottom = size.height) { drawBars(1f) }
        drawLine(Color.White, Offset(xNow, 0f), Offset(xNow, size.height), strokeWidth = 3f)
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
