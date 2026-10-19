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

    suspend fun recordSession(s: SessionSummary) {
        dao.insertSession(
            SessionEntity(
                planName = s.planName, startedAt = s.startedAt, durationSec = s.durationSec,
                distanceM = s.distanceM, avgHr = s.avgHr, maxHr = s.maxHr, avgSpeedKmh = s.avgSpeedKmh,
            ),
        )
        if (s.completed) s.scheduleId?.let { dao.markDone(it) }
    }

    suspend fun seedIfEmpty() {
        if (dao.planCount() > 0) return
        fun step(min: Double, speed: Double, incl: Double, label: String) =
            StepEntity(planId = 0, position = 0, durationSec = (min * 60).toInt(), speedKmh = speed, inclinePct = incl, label = label)
        savePlan(
            PlanEntity(name = "Marche active 30 min", description = "Échauffement, rythme soutenu, retour au calme"),
            listOf(step(5.0, 4.5, 1.0, "Échauffement"), step(20.0, 6.0, 2.0, "Allure"), step(5.0, 4.0, 0.0, "Retour au calme")),
        )
        savePlan(
            PlanEntity(name = "Fractionné 8 x 1'/1'", description = "8 répétitions rapide / récupération"),
            listOf(step(8.0, 7.0, 1.0, "Échauffement")) +
                (1..8).flatMap { listOf(step(1.0, 12.0, 1.0, "Rapide $it"), step(1.0, 7.0, 1.0, "Récup $it")) } +
                step(5.0, 5.0, 0.0, "Retour au calme"),
        )
        savePlan(
            PlanEntity(name = "Côtes 40 min", description = "Montées progressives"),
            listOf(step(5.0, 5.0, 1.0, "Échauffement")) +
                listOf(3.0, 5.0, 7.0, 9.0, 7.0, 5.0, 3.0).map { step(4.0, 6.5, it, "Pente ${it.toInt()} %") } +
                step(7.0, 4.5, 0.0, "Retour au calme"),
        )
    }
}

fun PlanWithSteps.toWorkoutSteps(): List<WorkoutStep> =
    orderedSteps.map { WorkoutStep(it.durationSec, it.speedKmh, it.inclinePct, it.label) }
