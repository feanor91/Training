package com.feanor91.treadmill.data

import android.content.Context
import com.feanor91.treadmill.domain.Sex
import com.feanor91.treadmill.domain.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Profil physique de l'utilisateur, persisté en SharedPreferences. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val defaults = UserProfile()

    private val _profile = MutableStateFlow(
        UserProfile(
            weightKg = prefs.getFloat("weightKg", defaults.weightKg.toFloat()).toDouble(),
            heightCm = prefs.getFloat("heightCm", defaults.heightCm.toFloat()).toDouble(),
            age = prefs.getInt("age", defaults.age),
            sex = runCatching { Sex.valueOf(prefs.getString("sex", null) ?: "") }.getOrDefault(defaults.sex),
        ),
    )
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _useHealthConnect = MutableStateFlow(prefs.getBoolean("useHealthConnect", true))
    /** Si vrai, poids et taille suivent Health Connect (dernière mesure) à chaque ouverture de l'onglet Santé. */
    val useHealthConnect: StateFlow<Boolean> = _useHealthConnect.asStateFlow()

    fun setUseHealthConnect(value: Boolean) {
        _useHealthConnect.value = value
        prefs.edit().putBoolean("useHealthConnect", value).apply()
    }

    fun update(transform: (UserProfile) -> UserProfile) {
        val p = transform(_profile.value).let {
            it.copy(weightKg = it.weightKg.coerceIn(30.0, 300.0), heightCm = it.heightCm.coerceIn(120.0, 230.0), age = it.age.coerceIn(10, 110))
        }
        _profile.value = p
        prefs.edit()
            .putFloat("weightKg", p.weightKg.toFloat()).putFloat("heightCm", p.heightCm.toFloat())
            .putInt("age", p.age).putString("sex", p.sex.name).apply()
    }
}
