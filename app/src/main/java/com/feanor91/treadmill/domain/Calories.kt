package com.feanor91.treadmill.domain

enum class Sex { Male, Female }

/** Profil utilisé pour estimer les calories (modifiable dans l'onglet Santé). */
data class UserProfile(
    val weightKg: Double = 97.0,
    val heightCm: Double = 172.0,
    val age: Int = 62,
    val sex: Sex = Sex.Male,
    /** Fréquence cardiaque maximale saisie ; null = estimée à partir de l'âge. */
    val maxHrOverride: Int? = null,
)

/** Estimations énergétiques pures (sans dépendance Android). */
object Calories {
    private const val KCAL_PER_L_O2 = 5.0
    private const val WALK_MAX_KMH = 6.0
    private const val RUN_MIN_KMH = 8.0

    /**
     * Dépense active (nette, hors métabolisme de repos) en kcal/min sur tapis, équations ACSM :
     * marche  VO2 = 0,1·S + 1,8·S·G ; course VO2 = 0,2·S + 0,9·S·G  (S en m/min, G = pente en fraction),
     * en ml/kg/min au-dessus du repos. Entre 6 et 8 km/h on interpole.
     */
    fun activeKcalPerMin(speedKmh: Double, inclinePct: Double, weightKg: Double): Double {
        if (speedKmh <= 0.0 || weightKg <= 0.0) return 0.0
        val s = speedKmh * 1000.0 / 60.0
        val g = (inclinePct / 100.0).coerceAtLeast(0.0)
        val walk = 0.1 * s + 1.8 * s * g
        val run = 0.2 * s + 0.9 * s * g
        val vo2 = when {
            speedKmh <= WALK_MAX_KMH -> walk
            speedKmh >= RUN_MIN_KMH -> run
            else -> {
                val t = (speedKmh - WALK_MAX_KMH) / (RUN_MIN_KMH - WALK_MAX_KMH)
                walk + (run - walk) * t
            }
        }
        return vo2 * weightKg / 1000.0 * KCAL_PER_L_O2
    }

    /**
     * Pas par seconde estimés sur tapis : longueur de pas = taille × coefficient croissant avec la vitesse
     * (≈ 0,41 à 4,5 km/h, ≈ 0,47 à 8 km/h), cadence = vitesse / longueur de pas.
     * Le tapis ne fournit pas de nombre de pas : c'est une estimation.
     */
    fun stepsPerSecond(speedKmh: Double, heightCm: Double): Double {
        if (speedKmh <= 0.0 || heightCm <= 0.0) return 0.0
        val stepLengthM = heightCm / 100.0 * (0.35 + 0.015 * speedKmh).coerceIn(0.38, 0.5)
        return speedKmh / 3.6 / stepLengthM
    }

    /** Métabolisme de base journalier (Mifflin-St Jeor), en kcal. */
    fun bmrPerDay(p: UserProfile): Double {
        val base = 10.0 * p.weightKg + 6.25 * p.heightCm - 5.0 * p.age
        return base + if (p.sex == Sex.Male) 5.0 else -161.0
    }
}
