package com.feanor91.treadmill.wear

import android.content.Context
import android.util.Log
import com.feanor91.treadmill.domain.WorkoutEngine
import com.feanor91.treadmill.domain.WorkoutState
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Pont avec la montre (Wear OS) : reçoit le rythme cardiaque mesuré au poignet et
 * envoie l'état de la séance pour l'afficher sur la montre.
 */
class WearBridge(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val messages by lazy { Wearable.getMessageClient(app) }
    private val nodes by lazy { Wearable.getNodeClient(app) }

    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _watchName = MutableStateFlow<String?>(null)
    val watchName: StateFlow<String?> = _watchName.asStateFlow()

    private val _watchHr = MutableStateFlow<Int?>(null)
    /** Dernier rythme cardiaque reçu ; repasse à null après [HR_TIMEOUT_MS] sans nouvelle mesure. */
    val watchHr: StateFlow<Int?> = _watchHr.asStateFlow()

    private var expiry: Job? = null
    private var lastState: WorkoutState = WorkoutState.Idle

    private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == WearProtocol.PATH_HEART_RATE) {
            String(event.data, Charsets.UTF_8).toIntOrNull()?.takeIf { it in 30..250 }?.let(::onHeartRate)
        }
    }

    private fun onHeartRate(bpm: Int) {
        _watchHr.value = bpm
        expiry?.cancel()
        expiry = scope.launch {
            delay(HR_TIMEOUT_MS)
            _watchHr.value = null
        }
    }

    /** À appeler une fois le moteur créé. */
    fun attach(engine: WorkoutEngine) {
        runCatching { messages.addListener(listener) }.onFailure { Log.w(TAG, "Wearable indisponible", it) }

        // Présence de la montre (appairage Wear OS).
        scope.launch {
            while (true) {
                refreshNodes()
                delay(15_000)
            }
        }

        // État de séance -> montre.
        scope.launch {
            engine.ui.collect { ui ->
                val running = ui.state == WorkoutState.Running || ui.state == WorkoutState.Paused
                when {
                    running && lastState != WorkoutState.Running && lastState != WorkoutState.Paused -> {
                        send(WearProtocol.PATH_WORKOUT_START, ByteArray(0))
                    }
                    !running && (lastState == WorkoutState.Running || lastState == WorkoutState.Paused) -> {
                        send(WearProtocol.PATH_WORKOUT_STOP, ByteArray(0))
                    }
                }
                lastState = ui.state
                if (running) {
                    val json = JSONObject()
                        .put("state", ui.state.name)
                        .put("countdown", ui.countdownSec)
                        .put("speed", ui.speedKmh)
                        .put("incline", ui.inclinePct)
                        .put("elapsed", ui.elapsedSec)
                        .put("remaining", if (ui.isUnlimited) -1 else (ui.totalSec - ui.elapsedSec).coerceAtLeast(0))
                        .put("step", ui.currentStep?.label ?: "")
                        .put("kcal", ui.kcal)
                    send(WearProtocol.PATH_STATE, json.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private suspend fun refreshNodes() {
        runCatching { nodes.connectedNodes.await() }
            .onSuccess { list ->
                _watchConnected.value = list.isNotEmpty()
                _watchName.value = list.firstOrNull()?.displayName
            }
            .onFailure { _watchConnected.value = false }
    }

    private suspend fun send(path: String, data: ByteArray) {
        runCatching {
            nodes.connectedNodes.await().forEach { messages.sendMessage(it.id, path, data).await() }
        }.onFailure { Log.d(TAG, "Envoi $path impossible : ${it.message}") }
    }

    private companion object {
        const val TAG = "WearBridge"
        const val HR_TIMEOUT_MS = 10_000L
    }
}
