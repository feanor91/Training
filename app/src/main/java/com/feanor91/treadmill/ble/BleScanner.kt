package com.feanor91.treadmill.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.feanor91.treadmill.domain.BleDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Émet la liste cumulée des appareils annonçant [service] ; s'arrête à l'annulation du collecteur. */
    fun scan(service: UUID): Flow<List<BleDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth indisponible ou désactivé"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, BleDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: "Appareil ${r.device.address.takeLast(5)}"
                found[r.device.address] = BleDevice(r.device.address, name, r.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Échec du scan BLE ($errorCode)"))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
