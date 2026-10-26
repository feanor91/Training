package com.feanor91.treadmill.wear

/** Chemins des messages Wearable Data Layer échangés avec l'application de la montre. */
object WearProtocol {
    /** Téléphone -> montre : une séance démarre / se termine. */
    const val PATH_WORKOUT_START = "/workout/start"
    const val PATH_WORKOUT_STOP = "/workout/stop"

    /** Téléphone -> montre : état courant de la séance (JSON, voir [WorkoutSnapshot]). */
    const val PATH_STATE = "/state"

    /** Montre -> téléphone : fréquence cardiaque, texte décimal en UTF-8. */
    const val PATH_HEART_RATE = "/hr"

    /** Montre -> téléphone : ajustement de la vitesse, delta en km/h (texte décimal, ex. « 0.5 » ou « -0.5 »). */
    const val PATH_SPEED_DELTA = "/speed"

    /** Montre -> téléphone : arrêt d'urgence de la séance. */
    const val PATH_EMERGENCY_STOP = "/stop"
}
