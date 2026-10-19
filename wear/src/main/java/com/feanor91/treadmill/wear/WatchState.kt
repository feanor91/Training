package com.feanor91.treadmill.wear

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Chemins des messages Data Layer (miroir de l'appli téléphone). */
object Protocol {
    const val PATH_WORKOUT_START = "/workout/start"
    const val PATH_WORKOUT_STOP = "/workout/stop"
    const val PATH_STATE = "/state"
    const val PATH_HEART_RATE = "/hr"
}

/** Dernier état de séance reçu du téléphone. */
data class PhoneState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val countdown: Int = 0,
    val speedKmh: Double = 0.0,
    val inclinePct: Double = 0.0,
    val elapsedSec: Int = 0,
    val remainingSec: Int = -1,
    val step: String = "",
    val kcal: Int = 0,
) {
    companion object {
        fun parse(json: String): PhoneState? = runCatching {
            val o = JSONObject(json)
            PhoneState(
                running = true,
                paused = o.optString("state") == "Paused",
                countdown = o.optInt("countdown"),
                speedKmh = o.optDouble("speed"),
                inclinePct = o.optDouble("incline"),
                elapsedSec = o.optInt("elapsed"),
                remainingSec = o.optInt("remaining", -1),
                step = o.optString("step"),
                kcal = o.optInt("kcal"),
            )
        }.getOrNull()
    }
}

/** État partagé entre le service de mesure, l'écoute du téléphone et l'interface. */
object WatchState {
    val heartRate = MutableStateFlow<Int?>(null)
    val measuring = MutableStateFlow(false)
    val phone = MutableStateFlow(PhoneState())
    val error = MutableStateFlow<String?>(null)
}
