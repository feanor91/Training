package com.feanor91.treadmill.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.feanor91.treadmill.domain.Calories
import com.feanor91.treadmill.domain.UserProfile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HcAvailability { Available, NeedsInstallOrUpdate, Unsupported }

/** Bilan énergétique d'une journée (kcal). Les valeurs nulles = non fournies par Health Connect. */
data class DayBalance(
    val date: LocalDate,
    val intakeKcal: Double?,
    val activeKcal: Double,
    val basalKcal: Double,
    val reportedTotalKcal: Double?,
) {
    /** Dépense totale retenue : la plus élevée entre le total déclaré par les applis et repos + actif. */
    val burnedKcal: Double get() = maxOf(basalKcal + activeKcal, reportedTotalKcal ?: 0.0)

    /** Déficit si > 0 (dépensé − consommé) ; null sans données de nutrition. */
    val deficitKcal: Double? get() = intakeKcal?.let { burnedKcal - it }
}

/** Séance à exporter vers Health Connect. */
data class ExportableSession(
    val title: String,
    val startedAtMs: Long,
    val durationSec: Int,
    val distanceM: Int,
    val activeKcal: Int,
    val avgSpeedKmh: Double,
    val samples: List<com.feanor91.treadmill.domain.Sample> = emptyList(),
)

/**
 * Accès à Health Connect : export des séances, lecture des calories des autres applis.
 * Health Connect exige Android 9+ ; sur Android 7-8 tout est simplement indisponible.
 */
class HealthConnectGateway(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )

    private val writePermissions = permissions.filter { it.contains("WRITE") }.toSet()

    fun availability(): HcAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return HcAvailability.Unsupported
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.NeedsInstallOrUpdate
            else -> HcAvailability.Unsupported
        }
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> =
        if (availability() == HcAvailability.Available) client.permissionController.getGrantedPermissions() else emptySet()

    suspend fun canWrite() = granted().containsAll(writePermissions)

    /** Écrit séance, distance, calories actives, vitesse et rythme cardiaque. */
    suspend fun export(s: ExportableSession): Boolean {
        if (!canWrite()) return false
        val start = Instant.ofEpochMilli(s.startedAtMs)
        val end = start.plusSeconds(s.durationSec.coerceAtLeast(1).toLong())
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        val meta = { Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE)) }

        val records = mutableListOf<androidx.health.connect.client.records.Record>()
        records += ExerciseSessionRecord(
            startTime = start, startZoneOffset = offset, endTime = end, endZoneOffset = offset,
            metadata = meta(),
            exerciseType = if (s.avgSpeedKmh >= 7.0) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            else ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            title = s.title,
        )
        if (s.distanceM > 0) {
            records += DistanceRecord(start, offset, end, offset, Length.meters(s.distanceM.toDouble()), meta())
        }
        if (s.activeKcal > 0) {
            records += ActiveCaloriesBurnedRecord(start, offset, end, offset, Energy.kilocalories(s.activeKcal.toDouble()), meta())
        }
        val inRange = s.samples.map { it.copy(timeMs = it.timeMs.coerceIn(start.toEpochMilli(), end.toEpochMilli() - 1)) }
            .distinctBy { it.timeMs }
        val hr = inRange.filter { it.heartRate != null }
            .map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.timeMs), it.heartRate!!.toLong()) }
        if (hr.isNotEmpty()) records += HeartRateRecord(start, offset, end, offset, hr, meta())
        val speed = inRange.filter { it.speedKmh > 0 }
            .map { SpeedRecord.Sample(Instant.ofEpochMilli(it.timeMs), Velocity.metersPerSecond(it.speedKmh / 3.6)) }
        if (speed.isNotEmpty()) records += SpeedRecord(start, offset, end, offset, speed, meta())

        client.insertRecords(records)
        return true
    }

    /** Bilan de la journée [date] ; pour aujourd'hui, le métabolisme de repos est proratisé à l'heure courante. */
    suspend fun dayBalance(date: LocalDate, profile: UserProfile): DayBalance {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val fullEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val end = if (now.isBefore(fullEnd)) now else fullEnd
        val agg: AggregationResult = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    NutritionRecord.ENERGY_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        )
        val dayFraction = Duration.between(start, end).seconds / Duration.between(start, fullEnd).seconds.toDouble()
        val basal = agg[BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL]?.inKilocalories?.takeIf { it > 0 }
            ?: (Calories.bmrPerDay(profile) * dayFraction)
        return DayBalance(
            date = date,
            intakeKcal = agg[NutritionRecord.ENERGY_TOTAL]?.inKilocalories?.takeIf { it > 0 },
            activeKcal = agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            basalKcal = basal,
            reportedTotalKcal = agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.takeIf { it > 0 },
        )
    }

    /** Dernier poids (kg) et dernière taille (cm) connus de Health Connect. */
    suspend fun latestBody(): Pair<Double?, Double?> {
        val before = TimeRangeFilter.before(Instant.now())
        val w = client.readRecords(ReadRecordsRequest(WeightRecord::class, before, ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.weight?.inKilograms
        val h = client.readRecords(ReadRecordsRequest(HeightRecord::class, before, ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.height?.inMeters?.times(100.0)
        return w to h
    }
}
