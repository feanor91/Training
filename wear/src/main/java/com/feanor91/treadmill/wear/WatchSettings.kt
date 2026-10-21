package com.feanor91.treadmill.wear

import android.content.Context

/** Réglages de la montre (stockés localement). */
object WatchSettings {
    private const val PREFS = "watch_settings"
    private const val KEY_SCREEN_ON = "keep_screen_on"

    /** Écran allumé en permanence tant que l'appli est ouverte (défaut), sinon extinction normale de la montre. */
    fun keepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_ON, value).apply()
    }
}
