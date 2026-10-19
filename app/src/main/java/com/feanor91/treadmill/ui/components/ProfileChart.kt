package com.feanor91.treadmill.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.feanor91.treadmill.domain.WorkoutStep

/**
 * Profil de séance : largeur = durée, hauteur = vitesse, teinte = pente.
 * [highlight] met en évidence le palier courant ; les paliers passés sont atténués.
 */
@Composable
fun ProfileChart(
    steps: List<WorkoutStep>,
    base: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    highlight: Int = -1,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (steps.isEmpty()) return@Canvas
        val weights = steps.map { (if (it.durationSec > 0) it.durationSec else 60).toFloat() }
        val total = weights.sum()
        val maxSpeed = (steps.maxOf { it.speedKmh }).coerceAtLeast(1.0).toFloat()
        val maxIncline = (steps.maxOf { it.inclinePct }).coerceAtLeast(1.0).toFloat()
        val gap = 1.5f
        var x = 0f
        steps.forEachIndexed { i, s ->
            val w = size.width * weights[i] / total
            val h = size.height * (s.speedKmh.toFloat() / maxSpeed).coerceIn(0.08f, 1f)
            val mix = (s.inclinePct.toFloat() / maxIncline).coerceIn(0f, 1f)
            val c = lerp(base, accent, mix)
            val alpha = when {
                highlight < 0 -> 1f
                i == highlight -> 1f
                i < highlight -> 0.35f
                else -> 0.65f
            }
            drawRect(c.copy(alpha = alpha), Offset(x, size.height - h), Size((w - gap).coerceAtLeast(1f), h))
            x += w
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
