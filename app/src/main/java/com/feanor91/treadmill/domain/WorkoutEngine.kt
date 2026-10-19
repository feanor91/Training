package com.feanor91.treadmill.domain

import com.feanor91.treadmill.ble.FtmsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** État affiché par l'écran de séance et la notification. */
data class WorkoutUi(
    val state: WorkoutState = WorkoutState.Idle,
    val planName: String = "",
    val steps: List<WorkoutStep> = emptyList(),
    val stepIndex: Int = 0,
    val stepRemainingSec: Int = 0,
    val elapsedSec: Int = 0,
    /** 0 = séance sans limite de durée (course libre). */
    val totalSec: Int = 0,
    val targetSpeed: Double = 0.0,
    val targetIncline: Double = 0.0,
    val speedKmh: Double = 0.0,
    val inclinePct: Double = 0.0,
    val heartRate: Int? = null,
    val distanceM: Int = 0,
    val summary: SessionSummary? = null,
    /** > 0 pendant le compte à rebours précédant le démarrage du tapis. */
    val countdownSec: Int = 0,
    /** Dépense active estimée depuis le début de la séance. */
    val kcal: Int = 0,
) {
    val currentStep: WorkoutStep? get() = steps.getOrNull(stepIndex)
    val nextStep: WorkoutStep? get() = steps.getOrNull(stepIndex + 1)
    val isUnlimited get() = totalSec == 0
}

/**
 * Machine à états d'une séance : déroule les paliers, pousse vitesse / inclinaison au tapis
 * (bornées aux limites lues sur la machine) et agrège les statistiques.
 */
class WorkoutEngine(
    private val ftms: FtmsClient,
    /** Cardio externe (ceinture, montre) ; le cardio du tapis sert de repli. */
    private val externalHeartRate: () -> Int?,
    private val scope: CoroutineScope,
    private val profile: () -> UserProfile,
    private val onFinished: suspend (SessionSummary) -> Unit,
) {
    private val _ui = MutableStateFlow(WorkoutUi())
    val ui: StateFlow<WorkoutUi> = _ui.asStateFlow()

    private companion object {
        const val COUNTDOWN_SEC = 5
        const val SAMPLE_EVERY_SEC = 5
    }

    private var job: Job? = null
    private var scheduleId: Long? = null
    private var startedAt = 0L
    private var distanceBase: Int? = null
    private var integratedM = 0.0
    private var speedSum = 0.0
    private var speedN = 0
    private var hrSum = 0L
    private var hrN = 0
    private var hrMax = 0
    private var kcalAcc = 0.0
    private val samples = ArrayList<Sample>()

    val running get() = _ui.value.state == WorkoutState.Running || _ui.value.state == WorkoutState.Paused

    fun start(name: String, steps: List<WorkoutStep>, scheduleId: Long? = null) {
        if (running || steps.isEmpty()) return
        this.scheduleId = scheduleId
        distanceBase = ftms.metrics.value.distanceM
        integratedM = 0.0; kcalAcc = 0.0; samples.clear(); speedSum = 0.0; speedN = 0; hrSum = 0; hrN = 0; hrMax = 0
        val limits = ftms.limits.value
        val fixed = steps.map { it.copy(speedKmh = limits.clampSpeed(it.speedKmh), inclinePct = limits.clampIncline(it.inclinePct)) }
        _ui.value = WorkoutUi(
            state = WorkoutState.Running, planName = name, steps = fixed,
            totalSec = if (fixed.any { it.durationSec == 0 }) 0 else fixed.sumOf { it.durationSec },
            targetSpeed = fixed[0].speedKmh, targetIncline = fixed[0].inclinePct, stepRemainingSec = fixed[0].durationSec,
            countdownSec = COUNTDOWN_SEC,
        )
        job = scope.launch {
            for (n in COUNTDOWN_SEC downTo 1) {
                _ui.update { it.copy(countdownSec = n) }
                delay(1_000)
            }
            _ui.update { it.copy(countdownSec = 0) }
            startedAt = System.currentTimeMillis()
            ftms.requestControl()
            ftms.start()
            enterStep(0)
            while (isActive) {
                delay(1_000)
                if (_ui.value.state == WorkoutState.Running) tick()
            }
        }
    }

    fun pause() {
        if (_ui.value.state != WorkoutState.Running || _ui.value.countdownSec > 0) return
        _ui.update { it.copy(state = WorkoutState.Paused) }
        scope.launch { ftms.pause() }
    }

    fun resume() {
        if (_ui.value.state != WorkoutState.Paused) return
        _ui.update { it.copy(state = WorkoutState.Running) }
        scope.launch {
            ftms.start()
            val u = _ui.value
            push(u.targetSpeed, u.targetIncline)
        }
    }

    fun stop() {
        if (!running) return
        if (_ui.value.countdownSec > 0) {
            // Annulation avant le départ : le tapis n'a pas été sollicité, rien à enregistrer.
            job?.cancel()
            _ui.value = WorkoutUi()
        } else {
            finish(completed = false)
        }
    }

    fun skipStep() {
        val u = _ui.value
        if (!running || u.countdownSec > 0) return
        if (u.stepIndex + 1 < u.steps.size) enterStep(u.stepIndex + 1) else finish(completed = true)
    }

    /** Ajustement manuel ; reste valable jusqu'au palier suivant. */
    fun adjustSpeed(deltaKmh: Double) {
        if (!running || _ui.value.countdownSec > 0) return
        val v = ftms.limits.value.clampSpeed(_ui.value.targetSpeed + deltaKmh)
        _ui.update { it.copy(targetSpeed = v) }
        scope.launch { ftms.setSpeed(v) }
    }

    fun adjustIncline(deltaPct: Double) {
        if (!running || _ui.value.countdownSec > 0) return
        val v = ftms.limits.value.clampIncline(_ui.value.targetIncline + deltaPct)
        _ui.update { it.copy(targetIncline = v) }
        scope.launch { ftms.setIncline(v) }
    }

    /** Referme l'écran de bilan et revient à l'état repos. */
    fun dismiss() {
        if (_ui.value.state == WorkoutState.Finished) _ui.value = WorkoutUi()
    }

    private fun enterStep(index: Int) {
        val step = _ui.value.steps[index]
        _ui.update {
            it.copy(stepIndex = index, stepRemainingSec = step.durationSec, targetSpeed = step.speedKmh, targetIncline = step.inclinePct)
        }
        scope.launch { push(step.speedKmh, step.inclinePct) }
    }

    private suspend fun push(speed: Double, incline: Double) {
        ftms.setSpeed(speed)
        ftms.setIncline(incline)
    }

    private fun tick() {
        val m = ftms.metrics.value
        val u = _ui.value
        // Sans tapis connecté : mode simulé, les consignes tiennent lieu de mesures.
        val simulated = ftms.state.value != ConnectionState.Ready
        val speed = if (simulated) u.targetSpeed else m.speedKmh ?: 0.0
        val incline = if (simulated) u.targetIncline else m.inclinePct ?: u.targetIncline
        val hr = externalHeartRate() ?: m.heartRate

        kcalAcc += Calories.activeKcalPerMin(speed, incline, profile().weightKg) / 60.0
        integratedM += speed / 3.6
        speedSum += speed; speedN++
        if (hr != null) { hrSum += hr; hrN++; hrMax = maxOf(hrMax, hr) }

        val dist = m.distanceM?.takeUnless { simulated }
            ?.let { d -> (d - (distanceBase ?: d)).coerceAtLeast(0) } ?: integratedM.toInt()

        val step = u.currentStep ?: return
        val remaining = if (step.durationSec == 0) 0 else u.stepRemainingSec - 1
        _ui.update {
            it.copy(
                elapsedSec = it.elapsedSec + 1, kcal = kcalAcc.toInt(), stepRemainingSec = remaining.coerceAtLeast(0),
                speedKmh = speed, inclinePct = incline, heartRate = hr, distanceM = dist,
            )
        }
        if ((u.elapsedSec + 1) % SAMPLE_EVERY_SEC == 0) samples += Sample(System.currentTimeMillis(), hr, speed)
        if (step.durationSec > 0 && remaining <= 0) skipStep()
    }

    private fun finish(completed: Boolean) {
        job?.cancel()
        val u = _ui.value
        val summary = SessionSummary(
            planName = u.planName, startedAt = startedAt, durationSec = u.elapsedSec, distanceM = u.distanceM,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0, maxHr = hrMax,
            avgSpeedKmh = if (speedN > 0) speedSum / speedN else 0.0,
            scheduleId = scheduleId, completed = completed,
            activeKcal = kcalAcc.toInt(), samples = samples.toList(),
        )
        _ui.update { it.copy(state = WorkoutState.Finished, summary = summary, speedKmh = 0.0) }
        scope.launch {
            ftms.stop()
            onFinished(summary)
        }
    }
}
