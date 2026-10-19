package com.feanor91.treadmill.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ProgramGoal(val label: String, val startMin: Int, val endMin: Int, val pattern: List<SessionKind>) {
    WEIGHT_LOSS("Perte de poids", 20, 45, listOf(SessionKind.STEADY, SessionKind.INTERVAL, SessionKind.INCLINE)),
    ENDURANCE("Endurance", 25, 50, listOf(SessionKind.STEADY, SessionKind.LONG, SessionKind.INCLINE)),
    FITNESS("Remise en forme", 15, 30, listOf(SessionKind.STEADY, SessionKind.INCLINE, SessionKind.STEADY)),
}

enum class SessionKind(val label: String) {
    STEADY("Cardio régulier"),
    INTERVAL("Fractionné doux"),
    INCLINE("Marche en pente"),
    LONG("Sortie longue"),
}

data class GeneratedSession(
    val date: LocalDate,
    val title: String,
    val description: String,
    val steps: List<WorkoutStep>,
) {
    val totalSec: Int get() = steps.sumOf { it.durationSec }
}

/**
 * Construit un programme progressif réparti sur un calendrier : durée et vitesse montent doucement,
 * échauffement à 2,5 km/h, retour au calme à 2 km/h, vitesse plafonnée à [maxSpeed].
 * Les paliers principaux visent la zone cardio 2 (régulier) ou 3 (efforts) et restent à vitesse fixe sans capteur.
 */
object ProgramGenerator {
    private const val WARM_SPEED = 2.5
    private const val COOL_SPEED = 2.0
    private const val REST_SPEED = 3.0
    private const val WARM_MIN = 5
    private const val COOL_MIN = 5

    fun trainingDates(start: LocalDate, totalDays: Int, weekdays: Set<DayOfWeek>): List<LocalDate> =
        (0 until totalDays).map { start.plusDays(it.toLong()) }.filter { it.dayOfWeek in weekdays }

    fun generate(
        goal: ProgramGoal,
        start: LocalDate,
        totalDays: Int,
        weekdays: Set<DayOfWeek>,
        maxSpeed: Double,
        limits: MachineLimits? = null,
    ): List<GeneratedSession> {
        val dates = trainingDates(start, totalDays, weekdays)
        val n = dates.size
        return dates.mapIndexed { i, date ->
            val t = if (n <= 1) 0.0 else i.toDouble() / (n - 1)
            val kind = goal.pattern[i % goal.pattern.size]
            val minutes = round5(lerp(goal.startMin.toDouble(), goal.endMin.toDouble(), t) * if (kind == SessionKind.LONG) 1.2 else 1.0).coerceAtLeast(15)
            val main = (minutes - WARM_MIN - COOL_MIN).coerceAtLeast(5)
            val base = speed(lerp(3.5, maxSpeed - 0.3, t), maxSpeed, limits)
            val week = ChronoUnit.DAYS.between(start, date).toInt() / 7 + 1
            GeneratedSession(
                date = date,
                title = "S$week · ${kind.label}",
                description = "${goal.label} · séance ${i + 1}/$n · $minutes min",
                steps = steps(kind, main, base, t, maxSpeed, limits),
            )
        }
    }

    private fun steps(kind: SessionKind, mainMin: Int, base: Double, t: Double, maxSpeed: Double, limits: MachineLimits?): List<WorkoutStep> {
        val warm = WorkoutStep(WARM_MIN * 60, speed(WARM_SPEED, maxSpeed, limits), 0.0, "Échauffement")
        val cool = WorkoutStep(COOL_MIN * 60, speed(COOL_SPEED, maxSpeed, limits), 0.0, "Retour au calme")
        val main: List<WorkoutStep> = when (kind) {
            SessionKind.STEADY, SessionKind.LONG -> listOf(
                WorkoutStep(
                    mainMin * 60, base, 0.0, "Zone 2", targetZone = 2,
                    minSpeedKmh = speed(base - 0.8, maxSpeed, limits), maxSpeedKmh = speed(min(maxSpeed, base + 0.6), maxSpeed, limits),
                ),
            )
            SessionKind.INCLINE -> {
                val inclines = listOf(1.0, 2.0, 3.0, 2.0)
                val blocks = max(1, (mainMin + 3) / 4)
                List(blocks) { k ->
                    val inc = limits?.clampIncline(inclines[k % inclines.size]) ?: inclines[k % inclines.size]
                    WorkoutStep(4 * 60, speed(base - 0.3, maxSpeed, limits), inc, "Pente ${inc.roundToInt()} %")
                }
            }
            SessionKind.INTERVAL -> {
                val workMin = 2 + t.roundToInt()
                val restMin = 2
                val reps = (mainMin.toDouble() / (workMin + restMin)).roundToInt().coerceIn(3, 8)
                val work = speed(min(maxSpeed, base + 0.4), maxSpeed, limits)
                (1..reps).flatMap {
                    listOf(
                        WorkoutStep(
                            workMin * 60, work, 0.0, "Effort $it", targetZone = 3,
                            minSpeedKmh = speed(work - 0.6, maxSpeed, limits), maxSpeedKmh = speed(maxSpeed, maxSpeed, limits),
                        ),
                        WorkoutStep(
                            restMin * 60, speed(REST_SPEED, maxSpeed, limits), 0.0, "Récup $it", targetZone = 1,
                            minSpeedKmh = speed(COOL_SPEED, maxSpeed, limits), maxSpeedKmh = speed(REST_SPEED, maxSpeed, limits),
                        ),
                    )
                }
            }
        }
        return listOf(warm) + main + cool
    }

    private fun speed(v: Double, maxSpeed: Double, limits: MachineLimits?): Double {
        val capped = min(v, maxSpeed)
        return limits?.clampSpeed(capped) ?: (Math.round(capped * 10.0) / 10.0)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun round5(v: Double) = ((v / 5.0).roundToInt() * 5)
}
