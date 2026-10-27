package com.feanor91.treadmill.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
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

/** Composition corporelle et indicateurs lus dans Health Connect. */
data class BodyComposition(
    val weightKg: Double? = null,
    val weightAt: Instant? = null,
    val heightCm: Double? = null,
    val bodyFatPct: Double? = null,
    val bodyFatAt: Instant? = null,
    val leanMassKg: Double? = null,
    val boneMassKg: Double? = null,
    val bodyWaterKg: Double? = null,
    val bmrKcalPerDay: Double? = null,
    val restingHr: Int? = null,
    val vo2Max: Double? = null,
    val stepsToday: Long? = null,
    /** Pesées des 90 derniers jours, de la plus ancienne à la plus récente. */
    val weightSeries: List<Pair<Instant, Double>> = emptyList(),
) {
    val bmi: Double? get() = if (weightKg != null && heightCm != null && heightCm > 0) weightKg / ((heightCm / 100.0) * (heightCm / 100.0)) else null
    val fatMassKg: Double? get() = if (weightKg != null && bodyFatPct != null) weightKg * bodyFatPct / 100.0 else null

    /** Masse maigre mesurée, sinon déduite du poids et du taux de graisse. */
    val leanOrDerivedKg: Double? get() = leanMassKg ?: fatMassKg?.let { weightKg?.minus(it) }
    val leanIsDerived: Boolean get() = leanMassKg == null && leanOrDerivedKg != null
    val hasAnything: Boolean get() = weightKg != null || bodyFatPct != null || restingHr != null || vo2Max != null || stepsToday != null
}

/** Pas du jour lus dans Health Connect (toutes applications confondues). */
data class StepsToday(
    val count: Long? = null,
    /** La lecture des pas n'est pas autorisée. */
    val permissionMissing: Boolean = false,
    /** Health Connect indisponible sur cet appareil. */
    val unavailable: Boolean = false,
    val readAtMs: Long = 0,
)

/** Séance à exporter vers Health Connect. */
data class ExportableSession(
    val title: String,
    val startedAtMs: Long,
    val durationSec: Int,
    val distanceM: Int,
    val activeKcal: Int,
    val avgSpeedKmh: Double,
    val steps: Int = 0,
    /** Part « repos » de la période (métabolisme de base), pour le total de calories que lisent d'autres applis. */
    val restingKcal: Int = 0,
    val samples: List<com.feanor91.treadmill.domain.Sample> = emptyList(),
)

/** Résultat d'un export : [report] détaille ce qui a été écrit et ce que Health Connect en retient. */
data class ExportResult(val sent: Boolean, val report: String = "")

/**
 * Accès à Health Connect : export des séances, lecture des calories des autres applis.
 * Health Connect exige Android 9+ ; sur Android 7-8 tout est simplement indisponible.
 */
class HealthConnectGateway(private val context: Context) {

    /** Accès de base : séances, calories, bilan, poids et taille. */
    val corePermissions: Set<String> = setOf(
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

    /** Composition corporelle et indicateurs de forme (ajoutés après la première version). */
    val bodyPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    val permissions: Set<String> = corePermissions + bodyPermissions

    /** Seules les écritures de base sont exigées pour exporter ; les pas sont ajoutés si autorisés. */
    private val writePermissions = corePermissions.filter { it.contains("WRITE") }.toSet()

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

    private suspend fun canWriteSteps() = HealthPermission.getWritePermission(StepsRecord::class) in granted()
    private suspend fun canWriteTotal() = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) in granted()

    /** Écrit séance, distance, calories actives, pas, vitesse et rythme cardiaque. */
    suspend fun export(s: ExportableSession): ExportResult {
        if (!canWrite()) return ExportResult(false)
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
        // Samsung Health lit les calories d'un entraînement dans le total (repos + actif) et non dans les calories actives.
        val totalAllowed = canWriteTotal()
        if (s.activeKcal > 0 && totalAllowed) {
            records += TotalCaloriesBurnedRecord(
                start, offset, end, offset, Energy.kilocalories((s.activeKcal + s.restingKcal).toDouble()), meta(),
            )
        }
        val stepsAllowed = canWriteSteps()
        if (s.steps > 0 && stepsAllowed) {
            records += StepsRecord(start, offset, end, offset, s.steps.toLong(), meta())
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
        return ExportResult(true, verify(s, start, end, stepsAllowed, totalAllowed))
    }

    /**
     * Relit ce que Health Connect contient pour la séance : ce que l'appli a écrit, et le total retenu toutes
     * applications confondues (Health Connect n'additionne pas des sources qui se chevauchent : celle qui a la
     * priorité l'emporte, souvent la montre).
     */
    private suspend fun verify(s: ExportableSession, start: Instant, end: Instant, stepsAllowed: Boolean, totalAllowed: Boolean): String {
        val range = TimeRangeFilter.between(start, end)
        val mine = setOf(DataOrigin(context.packageName))
        val lines = mutableListOf<String>()

        if (s.activeKcal > 0) {
            runCatching {
                val ours = client.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range, mine)).records
                    .sumOf { it.energy.inKilocalories }
                val kept = client.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                lines += "Calories : ${ours.toInt()} kcal écrites" + (kept?.let { " · retenues par Health Connect : ${it.toInt()} kcal" } ?: "")
            }.onFailure { lines += "Calories : ${s.activeKcal} kcal envoyées (relecture non autorisée)" }
        }
        if (s.activeKcal > 0 && !totalAllowed) {
            lines += "Total calories : NON envoyé (autorisation manquante, onglet Santé > Autoriser la composition corporelle)."
        }
        if (s.steps > 0) {
            if (!stepsAllowed) {
                lines += "Pas : NON envoyés, autorisez l'écriture des pas (onglet Santé > Autoriser la composition corporelle)."
            } else runCatching {
                val ours = client.readRecords(ReadRecordsRequest(StepsRecord::class, range, mine)).records.sumOf { it.count }
                val kept = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL]
                lines += "Pas : $ours écrits" + (kept?.let { " · retenus par Health Connect : $it" } ?: "")
            }.onFailure { lines += "Pas : ${s.steps} envoyés (relecture non autorisée)" }
        }
        return lines.joinToString("\n")
    }

    /** Bilan de la journée [date] ; pour aujourd'hui, le métabolisme de repos est proratisé à l'heure courante. */
    suspend fun dayBalance(date: LocalDate, bmrPerDay: Double): DayBalance {
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
            ?: (bmrPerDay * dayFraction)
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

    /** Nombre de pas depuis minuit, agrégé par Health Connect (montre, téléphone, séances de l'application). */
    suspend fun stepsToday(): StepsToday {
        if (availability() != HcAvailability.Available) return StepsToday(unavailable = true)
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted()) return StepsToday(permissionMissing = true)
        return runCatching {
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val now = Instant.now()
            val total = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now)))[StepsRecord.COUNT_TOTAL]
            StepsToday(count = total ?: 0L, readAtMs = now.toEpochMilli())
        }.getOrElse { StepsToday(unavailable = true) }
    }

    /** Dernières mesures de composition corporelle ; chaque champ absent ou non autorisé reste null. */
    suspend fun bodyComposition(): BodyComposition {
        val now = Instant.now()
        val before = TimeRangeFilter.before(now)

        suspend fun <T : androidx.health.connect.client.records.Record> latest(type: kotlin.reflect.KClass<T>): T? =
            runCatching {
                client.readRecords(ReadRecordsRequest(type, before, ascendingOrder = false, pageSize = 1)).records.firstOrNull()
            }.getOrNull()

        val weight = latest(WeightRecord::class)
        val height = latest(HeightRecord::class)
        val fat = latest(BodyFatRecord::class)
        val lean = latest(LeanBodyMassRecord::class)
        val bone = latest(BoneMassRecord::class)
        val water = latest(BodyWaterMassRecord::class)
        val bmr = latest(BasalMetabolicRateRecord::class)
        val resting = latest(RestingHeartRateRecord::class)
        val vo2 = latest(Vo2MaxRecord::class)

        val series = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    WeightRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(90)), now),
                    ascendingOrder = true,
                    pageSize = 500,
                ),
            ).records.map { it.time to it.weight.inKilograms }
        }.getOrDefault(emptyList())

        val steps = runCatching {
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()

        return BodyComposition(
            weightKg = weight?.weight?.inKilograms, weightAt = weight?.time,
            heightCm = height?.height?.inMeters?.times(100.0),
            bodyFatPct = fat?.percentage?.value, bodyFatAt = fat?.time,
            leanMassKg = lean?.mass?.inKilograms,
            boneMassKg = bone?.mass?.inKilograms,
            bodyWaterKg = water?.mass?.inKilograms,
            bmrKcalPerDay = bmr?.basalMetabolicRate?.inKilocaloriesPerDay,
            restingHr = resting?.beatsPerMinute?.toInt(),
            vo2Max = vo2?.vo2MillilitersPerMinuteKilogram,
            stepsToday = steps,
            weightSeries = series,
        )
    }
}
