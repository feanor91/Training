package com.feanor91.treadmill.wear

import android.content.Context

/** Réglages de la montre (stockés localement). */
object WatchSettings {
    private const val PREFS = "watch_settings"
    // Nouvelle clé : l'ancien réglage (écran allumé en permanence) n'avait pas le même sens.
    private const val KEY_SCREEN_ON = "keep_screen_on_session"

    /** Écran maintenu allumé pendant une séance (défaut) ; hors séance, la montre éteint son écran normalement. */
    fun keepScreenOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_ON, value).apply()
    }
}
