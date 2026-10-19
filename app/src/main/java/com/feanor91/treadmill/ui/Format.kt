package com.feanor91.treadmill.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatClock(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun formatSpeed(kmh: Double) = String.format(Locale.US, "%.1f", kmh)
fun formatIncline(pct: Double) = String.format(Locale.US, "%.1f", pct)
fun formatKm(meters: Int) = String.format(Locale.US, "%.2f", meters / 1000.0)

/** Allure en min/km ("--" à l'arrêt). */
fun formatPace(kmh: Double): String {
    if (kmh < 0.5) return "--:--"
    val secPerKm = (3600.0 / kmh).toInt()
    return String.format(Locale.US, "%d:%02d", secPerKm / 60, secPerKm % 60)
}

fun formatDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
