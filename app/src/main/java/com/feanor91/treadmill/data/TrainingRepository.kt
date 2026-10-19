package com.feanor91.treadmill.data

import com.feanor91.treadmill.domain.SessionSummary
import com.feanor91.treadmill.domain.WorkoutStep
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class TrainingRepository(private val db: AppDatabase) {
    private val dao = db.dao()

    val plans: Flow<List<PlanWithSteps>> = dao.observePlans()
    val sessions: Flow<List<SessionEntity>> = dao.observeSessions()

    fun today(): Flow<List<ScheduledItem>> = dao.observeDay(LocalDate.now().toEpochDay())
    fun upcoming(limit: Int = 30): Flow<List<ScheduledItem>> =
        dao.observeUpcoming(LocalDate.now().toEpochDay(), limit)

    suspend fun plan(id: Long) = dao.getPlan(id)
    suspend fun savePlan(plan: PlanEntity, steps: List<StepEntity>): Long = db.withTransaction {
        val id = if (plan.id == 0L) dao.insertPlan(plan) else { dao.updatePlan(plan); dao.deleteSteps(plan.id); plan.id }
        dao.insertSteps(steps.mapIndexed { i, s -> s.copy(id = 0, planId = id, position = i) })
        id
    }
    suspend fun deletePlan(id: Long) = dao.deletePlan(id)

    /** Programme [planId] à [start], puis chaque semaine pendant [weeks] semaines (1 = une seule fois). */
    suspend fun schedule(planId: Long, start: LocalDate, weeks: Int) {
        dao.insertSchedule((0 until weeks.coerceAtLeast(1)).map {
            ScheduleEntity(planId = planId, epochDay = start.plusWeeks(it.toLong()).toEpochDay())
        })
    }

    suspend fun unschedule(item: ScheduleEntity) = dao.deleteSchedule(item)
    suspend fun deleteSession(id: Long) = dao.deleteSession(id)

    /** Enregistre la séance et renvoie son id. */
    suspend fun recordSession(s: SessionSummary): Long {
        val id = dao.insertSession(
            SessionEntity(
                planName = s.planName, startedAt = s.startedAt, durationSec = s.durationSec,
                distanceM = s.distanceM, avgHr = s.avgHr, maxHr = s.maxHr, avgSpeedKmh = s.avgSpeedKmh,
                activeKcal = s.activeKcal, steps = s.steps,
            ),
        )
        if (s.completed) s.scheduleId?.let { dao.markDone(it) }
        return id
    }

    suspend fun markExported(id: Long) = dao.markExported(id)

    /** Insère les plans par défaut absents (par nom) ; sans effet sur ceux déjà présents. */
    suspend fun seedDefaults() {
        fun step(min: Double, speed: Double, incl: Double, label: String) =
            StepEntity(planId = 0, position = 0, durationSec = (min * 60).toInt(), speedKmh = speed, inclinePct = incl, label = label)

        val defaults = listOf(
            Triple("Marche active 30 min", "Échauffement, rythme soutenu, retour au calme",
                listOf(step(5.0, 4.5, 1.0, "Échauffement"), step(20.0, 6.0, 2.0, "Allure"), step(5.0, 4.0, 0.0, "Retour au calme"))),
            Triple("Fractionné 8 x 1'/1'", "8 répétitions rapide / récupération",
                listOf(step(8.0, 7.0, 1.0, "Échauffement")) +
                    (1..8).flatMap { listOf(step(1.0, 12.0, 1.0, "Rapide $it"), step(1.0, 7.0, 1.0, "Récup $it")) } +
                    step(5.0, 5.0, 0.0, "Retour au calme")),
            Triple("Côtes 40 min", "Montées progressives",
                listOf(step(5.0, 5.0, 1.0, "Échauffement")) +
                    listOf(3.0, 5.0, 7.0, 9.0, 7.0, 5.0, 3.0).map { step(4.0, 6.5, it, "Pente ${it.toInt()} %") } +
                    step(7.0, 4.5, 0.0, "Retour au calme")),

            // Plans doux : échauffement / récupération 2–3 km/h, effort 4–5 km/h maximum.
            Triple("Reprise douce 15 min", "Pour (re)démarrer en douceur",
                listOf(step(3.0, 2.0, 0.0, "Échauffement"), step(9.0, 3.5, 0.0, "Marche"), step(3.0, 2.0, 0.0, "Retour au calme"))),
            Triple("Marche progressive 30 min", "La vitesse monte par paliers jusqu'à 4,5 km/h",
                listOf(step(5.0, 2.5, 0.0, "Échauffement"), step(5.0, 3.5, 0.0, "Palier 3,5"), step(5.0, 4.0, 0.0, "Palier 4,0"),
                    step(5.0, 4.5, 0.0, "Palier 4,5"), step(5.0, 3.5, 0.0, "Récupération"), step(5.0, 2.0, 0.0, "Retour au calme"))),
            Triple("Fractionné doux 30 min", "5 x (2' soutenu / 2' récup), max 4,5 km/h",
                listOf(step(5.0, 2.5, 0.0, "Échauffement")) +
                    (1..5).flatMap { listOf(step(2.0, 4.5, 0.0, "Soutenu $it"), step(2.0, 3.0, 0.0, "Récup $it")) } +
                    step(5.0, 2.0, 0.0, "Retour au calme")),
            Triple("Fractionné 5 km/h 30 min", "4 x (3' à 5 km/h / 2' récup)",
                listOf(step(5.0, 2.5, 0.0, "Échauffement")) +
                    (1..4).flatMap { listOf(step(3.0, 5.0, 0.0, "Soutenu $it"), step(2.0, 3.0, 0.0, "Récup $it")) } +
                    step(5.0, 2.0, 0.0, "Retour au calme")),
            Triple("Marche en pente douce 30 min", "4 km/h, pente de 1 à 3 %",
                listOf(step(5.0, 2.5, 0.0, "Échauffement")) +
                    listOf(1.0, 2.0, 3.0, 2.0, 1.0).map { step(4.0, 4.0, it, "Pente ${it.toInt()} %") } +
                    step(5.0, 2.0, 0.0, "Retour au calme")),
        )
        for ((name, desc, steps) in defaults) {
            if (dao.planNameCount(name) == 0) savePlan(PlanEntity(name = name, description = desc), steps)
        }
    }
}

fun PlanWithSteps.toWorkoutSteps(): List<WorkoutStep> =
    orderedSteps.map { WorkoutStep(it.durationSec, it.speedKmh, it.inclinePct, it.label) }
