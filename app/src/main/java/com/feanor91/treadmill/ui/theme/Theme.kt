package com.feanor91.treadmill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF5EA), onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFFE8590C), tertiary = Color(0xFFD6336C),
    background = Color(0xFFF3F6F5), surface = Color(0xFFF3F6F5),
    surfaceVariant = Color(0xFFE3ECE9), onSurfaceVariant = Color(0xFF4A5A56),
    surfaceContainer = Color(0xFFEAF1EF), surfaceContainerHighest = Color.White,
    outline = Color(0xFF8AA09A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5FD3B5), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0B5445), onPrimaryContainer = Color(0xFFCCF5EA),
    secondary = Color(0xFFFFA94D), tertiary = Color(0xFFF783AC),
    background = Color(0xFF0D1311), surface = Color(0xFF0D1311),
    surfaceVariant = Color(0xFF22302C), onSurfaceVariant = Color(0xFFB5C6C0),
    surfaceContainer = Color(0xFF141C19), surfaceContainerHighest = Color(0xFF1A2420),
    outline = Color(0xFF70847E),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Dégradé émeraude des grandes cartes d'accueil (identique à celui du widget). */
val HeroBrush: Brush = Brush.linearGradient(listOf(Color(0xFF14B8A6), Color(0xFF0B6B5B), Color(0xFF065F46)))

@Composable
fun TreadmillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes, typography = AppTypography, content = content,
    )
}

/** Couleurs de l'écran de séance épuré (toujours sombre, fort contraste). */
object WorkoutColors {
    val Background = Color.Black
    val Speed = Color(0xFF5FD3B5)
    val Incline = Color(0xFFFFA94D)
    val Heart = Color(0xFFFF6B6B)
    val Dim = Color(0xFF9AA0A6)
}
