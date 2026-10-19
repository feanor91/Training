package com.feanor91.treadmill.domain

/** Limites physiques lues sur le tapis (caractéristiques FTMS 0x2AD4 / 0x2AD5). */
data class MachineLimits(
    val minSpeedKmh: Double = 1.0,
    val maxSpeedKmh: Double = 20.0,
    val speedStepKmh: Double = 0.1,
    val minInclinePct: Double = 0.0,
    val maxInclinePct: Double = 15.0,
    val inclineStepPct: Double = 0.5,
    /** false tant que les valeurs sont des défauts (tapis déconnecté ou plage non exposée). */
    val fromDevice: Boolean = false,
    val supportsIncline: Boolean = true,
) {
    fun clampSpeed(v: Double): Double = snap(v.coerceIn(minSpeedKmh, maxSpeedKmh), minSpeedKmh, speedStepKmh)
    fun clampIncline(v: Double): Double =
        if (!supportsIncline) 0.0 else snap(v.coerceIn(minInclinePct, maxInclinePct), minInclinePct, inclineStepPct)

    private fun snap(v: Double, min: Double, step: Double): Double {
        if (step <= 0.0) return v
        val n = Math.round((v - min) / step)
        return Math.round((min + n * step) * 100.0) / 100.0
    }
}

/** Dernière mesure reçue du tapis (champs absents = null). */
data class TreadmillMetrics(
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val distanceM: Int? = null,
    val heartRate: Int? = null,
    val energyKcal: Int? = null,
    val elapsedS: Int? = null,
)

enum class ConnectionState { Disconnected, Scanning, Connecting, Ready }

data class BleDevice(val address: String, val name: String, val rssi: Int)

data class WorkoutStep(
    val durationSec: Int,      // 0 = illimité (course libre)
    val speedKmh: Double,
    val inclinePct: Double,
    val label: String = "",
)

enum class WorkoutState { Idle, Running, Paused, Finished }

data class SessionSummary(
    val planName: String,
    val startedAt: Long,
    val durationSec: Int,
    val distanceM: Int,
    val avgHr: Int,
    val maxHr: Int,
    val avgSpeedKmh: Double,
    val scheduleId: Long?,
    val completed: Boolean,
)
