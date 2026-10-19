package com.feanor91.treadmill.ble

import android.annotation.SuppressLint
import android.content.Context
import com.feanor91.treadmill.domain.BleDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Ceinture / bracelet cardio BLE standard (service 0x180D), prioritaire sur le cardio du tapis. */
@SuppressLint("MissingPermission")
class HeartRateClient(private val context: Context) {
    private val scanner = BleScanner(context)
    private var gatt: BleGatt? = null

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    fun scan(): Flow<List<BleDevice>> = scanner.scan(Ftms.SERVICE_HEART_RATE)

    suspend fun connect(device: BleDevice): Boolean {
        disconnect()
        val adapter = scanner.adapter ?: return false
        val g = BleGatt(context, ::onNotify) { _heartRate.value = null; _deviceName.value = null }
        gatt = g
        val ok = g.connect(adapter.getRemoteDevice(device.address)) &&
            g.subscribe(Ftms.SERVICE_HEART_RATE, Ftms.CHAR_HEART_RATE)
        if (ok) _deviceName.value = device.name else disconnect()
        return ok
    }

    fun disconnect() {
        gatt?.close()
        gatt = null
        _heartRate.value = null
        _deviceName.value = null
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        if (uuid == Ftms.CHAR_HEART_RATE) _heartRate.value = FtmsParser.parseHeartRate(value)
    }
}
