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
    /** Pas estimés depuis le début de la séance. */
    val stepCount: Int = 0,
    /** Zone cardio actuelle (0 = sous la zone 1 ou pas de mesure) et FC max utilisée. */
    val hrZone: Int = 0,
    val maxHr: Int = 0,
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
        /**
         * Durée du compte à rebours interne du tapis (5 s sur le Care Fitness CT 750), qui démarre dès l'envoi
         * de la commande Start. Les consignes envoyées avant la fin sont ignorées par le tapis.
         */
        const val COUNTDOWN_SEC = 5
        const val SAMPLE_EVERY_SEC = 5
    }

    private var job: Job? = null
    private var scheduleId: Long? = null
    private var startedAt = 0L
    private var distanceBase: Int? = null
    private var energyBase: Int? = null
    private var integratedM = 0.0
    private var speedSum = 0.0
    private var speedN = 0
    private var hrSum = 0L
    private var hrN = 0
    private var hrMax = 0
    private var kcalAcc = 0.0
    private var stepsAcc = 0.0
    private val zoneSeconds = IntArray(6)
    private val hrWindow = ArrayDeque<Int>()
    private var stepTick = 0
    private var pushTick = 0
    private var pushRetries = 0
    /** La vitesse / la pente mesurées ont rejoint la consigne depuis le dernier envoi : tout écart ultérieur vient du tapis. */
    private var speedReached = false
    private var inclineReached = false
    private var lastMeasuredSpeed: Double? = null
    private var lastMeasuredIncline: Double? = null
    private var speedSteady = 0
    private var inclineSteady = 0
    private val samples = ArrayList<Sample>()

    val running get() = _ui.value.state == WorkoutState.Running || _ui.value.state == WorkoutState.Paused

    fun start(name: String, steps: List<WorkoutStep>, scheduleId: Long? = null) {
        if (running || steps.isEmpty()) return
        this.scheduleId = scheduleId
        distanceBase = ftms.metrics.value.distanceM
        integratedM = 0.0; kcalAcc = 0.0; stepsAcc = 0.0; zoneSeconds.fill(0); hrWindow.clear(); stepTick = 0; samples.clear(); speedSum = 0.0; speedN = 0; hrSum = 0; hrN = 0; hrMax = 0
        val limits = ftms.limits.value
        val fixed = steps.map { it.copy(speedKmh = limits.clampSpeed(it.speedKmh), inclinePct = limits.clampIncline(it.inclinePct)) }
        _ui.value = WorkoutUi(
            state = WorkoutState.Running, planName = name, steps = fixed,
            totalSec = if (fixed.any { it.durationSec == 0 }) 0 else fixed.sumOf { it.durationSec },
            targetSpeed = fixed[0].speedKmh, targetIncline = fixed[0].inclinePct, stepRemainingSec = fixed[0].durationSec,
            countdownSec = COUNTDOWN_SEC,
        )
        job = scope.launch {
            // Le tapis fait son propre compte à rebours après Start : on l'envoie tout de suite et on affiche
            // le décompte en parallèle ; la vitesse du premier palier n'est envoyée qu'une fois le tapis parti.
            launch {
                ftms.requestControl()
                ftms.start()
            }
            for (n in COUNTDOWN_SEC downTo 1) {
                _ui.update { it.copy(countdownSec = n) }
                delay(1_000)
            }
            _ui.update { it.copy(countdownSec = 0) }
            startedAt = System.currentTimeMillis()
            energyBase = ftms.metrics.value.energyKcal
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
            targetsPushed()
            push(u.targetSpeed, u.targetIncline)
        }
    }

    fun stop() {
        if (!running) return
        if (_ui.value.countdownSec > 0) {
            // Annulation pendant le décompte : le tapis a déjà reçu Start, on l'arrête ; rien à enregistrer.
            job?.cancel()
            scope.launch { ftms.stop() }
            _ui.value = WorkoutUi()
        } else {
            finish(completed = false)
        }
    }

    /**
     * Arrêt d'urgence (toucher l'écran, montre) : immédiat, sans confirmation, à n'importe quel moment
     * de la séance, y compris pendant le compte à rebours de départ.
     */
    fun emergencyStop() {
        if (!running) return
        if (_ui.value.countdownSec > 0) {
            job?.cancel()
            _ui.value = WorkoutUi()
            scope.launch { ftms.emergencyStop() }
        } else {
            finish(completed = false, emergency = true)
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
        targetsPushed()
        scope.launch { ftms.setSpeed(v) }
    }

    fun adjustIncline(deltaPct: Double) {
        if (!running || _ui.value.countdownSec > 0) return
        val v = ftms.limits.value.clampIncline(_ui.value.targetIncline + deltaPct)
        _ui.update { it.copy(targetIncline = v) }
        targetsPushed()
        scope.launch { ftms.setIncline(v) }
    }

    /** Referme l'écran de bilan et revient à l'état repos. */
    fun dismiss() {
        if (_ui.value.state == WorkoutState.Finished) _ui.value = WorkoutUi()
    }

    private fun enterStep(index: Int) {
        val step = _ui.value.steps[index]
        stepTick = 0
        hrWindow.clear()
        targetsPushed()
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

        kcalAcc += Calories.activeKcalPerMin(speed, incline, profile().weightKg) * profile().calorieFactor / 60.0
        stepsAcc += Calories.stepsPerSecond(speed, profile().heightCm)
        integratedM += speed / 3.6
        speedSum += speed; speedN++
        if (hr != null) { hrSum += hr; hrN++; hrMax = maxOf(hrMax, hr) }

        val dist = m.distanceM?.takeUnless { simulated }
            ?.let { d -> (d - (distanceBase ?: d)).coerceAtLeast(0) } ?: integratedM.toInt()

        val step = u.currentStep ?: return
        val maxHr = HeartZones.maxHr(profile())
        val zone = if (hr != null) HeartZones.zoneOf(hr, maxHr) else 0
        if (hr != null) zoneSeconds[zone]++
        stepTick++
        if (step.targetZone != null && hr != null) controlSpeed(step, u, hr, maxHr)
        if (!simulated && u.countdownSec == 0) enforceTargets(m.speedKmh, m.inclinePct)
        val remaining = if (step.durationSec == 0) 0 else u.stepRemainingSec - 1
        _ui.update {
            it.copy(
                elapsedSec = it.elapsedSec + 1, kcal = kcalAcc.toInt(), stepCount = stepsAcc.toInt(), stepRemainingSec = remaining.coerceAtLeast(0),
                speedKmh = speed, inclinePct = incline, heartRate = hr, distanceM = dist, hrZone = zone, maxHr = maxHr,
            )
        }
        if ((u.elapsedSec + 1) % SAMPLE_EVERY_SEC == 0) samples += Sample(System.currentTimeMillis(), hr, speed)
        if (step.durationSec > 0 && remaining <= 0) skipStep()
    }

    /**
     * Pilotage optionnel de la vitesse selon la zone cardio visée : toutes les 20 s (après 40 s de palier,
     * le cardio réagit avec retard) on compare la FC moyenne des 10 dernières secondes à la zone.
     * Trop haute : −0,2 km/h (−0,4 si très au-dessus) ; trop basse : +0,1 km/h. Toujours dans les bornes du palier.
     */
    private fun controlSpeed(step: WorkoutStep, u: WorkoutUi, hr: Int, maxHr: Int) {
        hrWindow.addLast(hr)
        while (hrWindow.size > 10) hrWindow.removeFirst()
        if (stepTick < 40 || stepTick % 20 != 0 || hrWindow.size < 5) return
        val avg = hrWindow.average()
        val range = HeartZones.range(step.targetZone ?: return, maxHr)
        // Garde-fou : au-delà de 92 % de la FC max on ralentit quelle que soit la zone demandée.
        val ceiling = minOf(range.last, (maxHr * 0.92).toInt())
        val limits = ftms.limits.value
        val lo = limits.clampSpeed(step.minSpeedKmh ?: (step.speedKmh - 1.0))
        val hi = limits.clampSpeed(step.maxSpeedKmh ?: (step.speedKmh + 1.0))
        val delta = when {
            avg > ceiling + 10 -> -0.4
            avg > ceiling + 2 -> -0.2
            avg < range.first - 2 -> +0.1
            else -> 0.0
        }
        if (delta == 0.0) return
        val v = limits.clampSpeed((u.targetSpeed + delta).coerceIn(minOf(lo, hi), maxOf(lo, hi)))
        if (v != u.targetSpeed) {
            _ui.update { it.copy(targetSpeed = v) }
            targetsPushed()
            scope.launch { ftms.setSpeed(v) }
        }
    }

    /** Une consigne vient d'être envoyée : on laisse 6 s au tapis avant de vérifier qu'elle est appliquée. */
    private fun targetsPushed() {
        pushTick = 0
        pushRetries = 0
        speedReached = false
        inclineReached = false
        speedSteady = 0
        inclineSteady = 0
    }

    /**
     * Le tapis ignore parfois une consigne (par exemple pendant son compte à rebours de départ) sans signaler
     * d'erreur : tant que la vitesse mesurée n'a pas rejoint la consigne, on la renvoie (6 s d'intervalle, 6 fois au plus).
     * Une fois la consigne atteinte, tout écart vient d'une action sur les commandes du tapis : on le respecte
     * et la valeur mesurée devient la nouvelle consigne (jusqu'au palier suivant), sans rien renvoyer.
     */
    private fun enforceTargets(measuredKmh: Double?, measuredIncline: Double?) {
        pushTick++
        adoptManualChanges(measuredKmh, measuredIncline)
        val measured = measuredKmh ?: return
        val u = _ui.value
        if (kotlin.math.abs(measured - u.targetSpeed) <= 0.35) return
        if (!speedReached && pushTick >= 6 && pushRetries < 6) {
            pushRetries++
            pushTick = 0
            scope.launch { push(u.targetSpeed, u.targetIncline) }
        }
    }

    private fun adoptManualChanges(speed: Double?, incline: Double?) {
        val u = _ui.value
        if (speed != null) {
            if (kotlin.math.abs(speed - u.targetSpeed) <= 0.35) {
                speedReached = true
                speedSteady = 0
            } else if (speedReached) {
                // On attend que la valeur se stabilise (le tapis monte ou descend progressivement).
                speedSteady = if (lastMeasuredSpeed?.let { kotlin.math.abs(speed - it) < 0.05 } == true) speedSteady + 1 else 0
                if (speedSteady >= 2) {
                    val v = ftms.limits.value.clampSpeed(Math.round(speed * 10) / 10.0)
                    _ui.update { it.copy(targetSpeed = v) }
                    speedSteady = 0
                }
            }
            lastMeasuredSpeed = speed
        }
        if (incline != null) {
            if (kotlin.math.abs(incline - u.targetIncline) <= 0.3) {
                inclineReached = true
                inclineSteady = 0
            } else if (inclineReached) {
                inclineSteady = if (lastMeasuredIncline?.let { kotlin.math.abs(incline - it) < 0.05 } == true) inclineSteady + 1 else 0
                if (inclineSteady >= 2) {
                    val v = ftms.limits.value.clampIncline(Math.round(incline * 10) / 10.0)
                    _ui.update { it.copy(targetIncline = v) }
                    inclineSteady = 0
                }
            }
            lastMeasuredIncline = incline
        }
    }

    /** Calories vues par le tapis depuis le départ de la séance ; le tapis remet parfois son compteur à zéro au Start. */
    private fun deviceKcal(): Int? {
        val now = ftms.metrics.value.energyKcal ?: return null
        val base = energyBase ?: 0
        return if (now >= base) now - base else now
    }

    private fun finish(completed: Boolean, emergency: Boolean = false) {
        job?.cancel()
        val u = _ui.value
        val summary = SessionSummary(
            planName = u.planName, startedAt = startedAt, durationSec = u.elapsedSec, distanceM = u.distanceM,
            avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0, maxHr = hrMax,
            avgSpeedKmh = if (speedN > 0) speedSum / speedN else 0.0,
            scheduleId = scheduleId, completed = completed,
            activeKcal = kcalAcc.toInt(), steps = stepsAcc.toInt(), samples = samples.toList(),
            zoneSeconds = zoneSeconds.toList(), deviceKcal = deviceKcal(),
        )
        _ui.update { it.copy(state = WorkoutState.Finished, summary = summary, speedKmh = 0.0) }
        scope.launch {
            if (emergency) ftms.emergencyStop() else ftms.stop()
            onFinished(summary)
        }
    }
}
