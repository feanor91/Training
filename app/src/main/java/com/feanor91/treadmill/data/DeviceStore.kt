package com.feanor91.treadmill.data

import android.content.Context
import com.feanor91.treadmill.domain.BleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dernier tapis et dernier capteur cardiaque connectés : permet la reconnexion automatique après une mise à jour. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    private fun load(prefix: String): BleDevice? {
        val address = prefs.getString("${prefix}Address", null) ?: return null
        return BleDevice(address, prefs.getString("${prefix}Name", null) ?: "Appareil", 0)
    }

    private val _treadmill = MutableStateFlow(load("treadmill"))
    val treadmill: StateFlow<BleDevice?> = _treadmill.asStateFlow()

    private val _strap = MutableStateFlow(load("strap"))
    val strap: StateFlow<BleDevice?> = _strap.asStateFlow()

    private val _autoReconnect = MutableStateFlow(prefs.getBoolean("autoReconnect", true))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun saveTreadmill(d: BleDevice) { save("treadmill", d); _treadmill.value = d }
    fun saveStrap(d: BleDevice) { save("strap", d); _strap.value = d }
    fun clearTreadmill() { clear("treadmill"); _treadmill.value = null }
    fun clearStrap() { clear("strap"); _strap.value = null }

    fun setAutoReconnect(value: Boolean) {
        _autoReconnect.value = value
        prefs.edit().putBoolean("autoReconnect", value).apply()
    }

    private fun save(prefix: String, d: BleDevice) {
        prefs.edit().putString("${prefix}Address", d.address).putString("${prefix}Name", d.name).apply()
    }

    private fun clear(prefix: String) {
        prefs.edit().remove("${prefix}Address").remove("${prefix}Name").apply()
    }
}
