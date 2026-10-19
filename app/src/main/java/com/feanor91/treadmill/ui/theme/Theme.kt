package com.feanor91.treadmill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0E7C66)
private val GreenLight = Color(0xFF5FD3B5)

private val Light = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    secondary = Color(0xFFE8590C), tertiary = Color(0xFFD6336C),
)
private val Dark = darkColorScheme(
    primary = GreenLight, onPrimary = Color(0xFF00382C),
    secondary = Color(0xFFFFA94D), tertiary = Color(0xFFF783AC),
)

@Composable
fun TreadmillTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Couleurs de l'écran de séance épuré (toujours sombre, fort contraste). */
object WorkoutColors {
    val Background = Color.Black
    val Speed = Color(0xFF5FD3B5)
    val Incline = Color(0xFFFFA94D)
    val Heart = Color(0xFFFF6B6B)
    val Dim = Color(0xFF9AA0A6)
}
