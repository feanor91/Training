package com.feanor91.treadmill.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
)

@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(PlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val position: Int,
    val durationSec: Int,
    val speedKmh: Double,
    val inclinePct: Double,
    val label: String = "",
)

/** Une séance programmée un jour donné (epochDay = LocalDate.toEpochDay()). */
@Entity(
    tableName = "schedule",
    foreignKeys = [ForeignKey(PlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("epochDay")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val epochDay: Long,
    val completed: Boolean = false,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planName: String,
    val startedAt: Long,
    val durationSec: Int,
    val distanceM: Int,
    val avgHr: Int,
    val maxHr: Int,
    val avgSpeedKmh: Double,
    val activeKcal: Int = 0,
    val exported: Boolean = false,
    val steps: Int = 0,
)

data class PlanWithSteps(
    @Embedded val plan: PlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId") val steps: List<StepEntity>,
) {
    val orderedSteps get() = steps.sortedBy { it.position }
    val totalSec get() = steps.sumOf { it.durationSec }
    val distanceKm get() = steps.sumOf { it.speedKmh * it.durationSec / 3600.0 }
}

data class ScheduledItem(
    @Embedded val schedule: ScheduleEntity,
    @Relation(entity = PlanEntity::class, parentColumn = "planId", entityColumn = "id")
    val plan: PlanWithSteps,
)
