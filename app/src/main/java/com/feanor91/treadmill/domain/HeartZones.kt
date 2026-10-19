package com.feanor91.treadmill.domain

/** Zones de fréquence cardiaque en pourcentage de la FC max (modèle à 5 zones). */
object HeartZones {
    val names = listOf("Très légère", "Légère", "Modérée", "Soutenue", "Maximale")
    private val lower = listOf(0.50, 0.60, 0.70, 0.80, 0.90)
    private val upper = listOf(0.60, 0.70, 0.80, 0.90, 1.00)

    /** FC max : valeur saisie, sinon formule de Tanaka (208 − 0,7 × âge), plus fiable que 220 − âge après 40 ans. */
    fun maxHr(p: UserProfile): Int = p.maxHrOverride ?: Math.round(208.0 - 0.7 * p.age).toInt()

    /** Zone (1-5) d'une fréquence ; 0 si sous la zone 1. */
    fun zoneOf(hr: Int, maxHr: Int): Int {
        if (maxHr <= 0) return 0
        val r = hr.toDouble() / maxHr
        return when {
            r < lower[0] -> 0
            r < upper[0] -> 1
            r < upper[1] -> 2
            r < upper[2] -> 3
            r < upper[3] -> 4
            else -> 5
        }
    }

    /** Bornes en bpm de la zone [zone] (1-5). */
    fun range(zone: Int, maxHr: Int): IntRange {
        val z = zone.coerceIn(1, 5) - 1
        return Math.round(lower[z] * maxHr).toInt()..Math.round(upper[z] * maxHr).toInt()
    }

    fun name(zone: Int): String = if (zone in 1..5) "Zone $zone · ${names[zone - 1]}" else "Sous la zone 1"
}
