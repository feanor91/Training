package com.feanor91.treadmill.data

import com.feanor91.treadmill.domain.GeneratedSession
import com.feanor91.treadmill.domain.SessionSummary
import com.feanor91.treadmill.domain.WorkoutStep
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class TrainingRepository(private val db: AppDatabase) {
    private val dao = db.dao()

    val plans: Flow<List<PlanWithSteps>> = dao.observePlans()
    val sessions: Flow<List<SessionEntity>> = dao.observeSessions()
    val programs: Flow<List<ProgramSummary>> = dao.observePrograms()

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

    /** Crée le programme, une séance (plan masqué) par date générée, et les inscrit au calendrier. */
    suspend fun createProgram(program: ProgramEntity, sessions: List<GeneratedSession>): Long = db.withTransaction {
        val programId = dao.insertProgram(program)
        for (s in sessions) {
            val planId = dao.insertPlan(PlanEntity(name = s.title, description = s.description, programId = programId))
            dao.insertSteps(s.steps.mapIndexed { i, st -> st.toEntity(planId, i) })
            dao.insertSchedule(listOf(ScheduleEntity(planId = planId, epochDay = s.date.toEpochDay())))
        }
        programId
    }

    /** Supprime le programme avec ses séances et leurs entrées de calendrier (suppression en cascade). */
    suspend fun deleteProgram(id: Long) = db.withTransaction {
        dao.deletePlansOfProgram(id)
        dao.deleteProgram(id)
    }

    suspend fun reminderRows(fromDay: Long) = dao.reminderRows(fromDay)
    suspend fun isScheduleCompleted(id: Long) = dao.isCompleted(id) ?: true

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

        /** Palier piloté par le cardio : la vitesse évolue entre [lo] et [hi] pour rester dans la zone [zone]. */
        fun zoned(min: Double, start: Double, lo: Double, hi: Double, zone: Int, incl: Double, label: String) =
            step(min, start, incl, label).copy(targetZone = zone, minSpeedKmh = lo, maxSpeedKmh = hi)

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
        val zoneDefaults = listOf(
            Triple("Zone 2 · endurance douce 30 min", "Vitesse ajustée automatiquement pour rester en zone 2 (cardio requis)",
                listOf(step(5.0, 2.5, 0.0, "Échauffement"), zoned(20.0, 3.5, 3.0, 4.5, 2, 0.0, "Zone 2"), step(5.0, 2.0, 0.0, "Retour au calme"))),
            Triple("Brûle-graisse zone 2 · 40 min", "30 min en zone 2 avec une pente légère (cardio requis)",
                listOf(step(5.0, 2.5, 0.0, "Échauffement"), zoned(30.0, 3.5, 3.0, 4.5, 2, 1.0, "Zone 2 · pente 1 %"), step(5.0, 2.0, 0.0, "Retour au calme"))),
            Triple("Cardio progressif zone 2 → 3 · 30 min", "Du facile au soutenu, vitesse pilotée par le cardio",
                listOf(step(5.0, 2.5, 0.0, "Échauffement"), zoned(8.0, 3.2, 2.8, 4.2, 2, 0.0, "Zone 2"), zoned(8.0, 3.8, 3.2, 4.8, 3, 0.0, "Zone 3"),
                    zoned(4.0, 3.2, 2.8, 4.0, 2, 0.0, "Zone 2"), step(5.0, 2.0, 0.0, "Retour au calme"))),
            Triple("Fractionné cardio zone 3/1 · 30 min", "4 x (3 min zone 3 / 2 min zone 1), vitesse max 5 km/h",
                listOf(step(5.0, 2.5, 0.0, "Échauffement")) +
                    (1..4).flatMap { listOf(zoned(3.0, 4.2, 3.5, 5.0, 3, 0.0, "Effort $it"), zoned(2.0, 2.8, 2.0, 3.0, 1, 0.0, "Récup $it")) } +
                    step(5.0, 2.0, 0.0, "Retour au calme")),
        )
        for ((name, desc, steps) in defaults + zoneDefaults) {
            if (dao.planNameCount(name) == 0) savePlan(PlanEntity(name = name, description = desc), steps)
        }
    }
}

fun PlanWithSteps.toWorkoutSteps(): List<WorkoutStep> =
    orderedSteps.map {
        WorkoutStep(it.durationSec, it.speedKmh, it.inclinePct, it.label, it.targetZone, it.minSpeedKmh, it.maxSpeedKmh)
    }

fun WorkoutStep.toEntity(planId: Long, position: Int) = StepEntity(
    planId = planId, position = position, durationSec = durationSec, speedKmh = speedKmh, inclinePct = inclinePct,
    label = label, targetZone = targetZone, minSpeedKmh = minSpeedKmh, maxSpeedKmh = maxSpeedKmh,
)
