package com.feanor91.treadmill.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Enveloppe d'un BluetoothGatt qui sérialise les opérations (une seule à la fois,
 * contrainte de la pile BLE Android) et les expose sous forme de fonctions suspend.
 */
@SuppressLint("MissingPermission") // permissions vérifiées en amont par l'UI
class BleGatt(
    private val context: Context,
    private val onNotify: (UUID, ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var lastRead: ByteArray? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) pending?.complete(-1)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                pending?.complete(if (status == 0) -1 else status)
                g.close()
                if (gatt === g) gatt = null
                onClosed()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        @Deprecated("Remplacé par la variante avec value en API 33, toujours appelé en repli")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastRead = c.value
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        @Deprecated("Remplacé par la variante avec value en API 33, toujours appelé en repli")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onNotify(c.uuid, it) }
        }
    }

    /** Connexion + découverte des services. */
    suspend fun connect(device: BluetoothDevice): Boolean = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        runCatching { withTimeout(20_000) { d.await() } }.getOrDefault(-1) == BluetoothGatt.GATT_SUCCESS
    }

    fun hasCharacteristic(service: UUID, ch: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(ch) != null

    suspend fun read(service: UUID, ch: UUID): ByteArray? = opLock.withLock {
        val c = gatt?.getService(service)?.getCharacteristic(ch) ?: return null
        val d = begin()
        lastRead = null
        if (gatt?.readCharacteristic(c) != true) return null
        if (await(d) != BluetoothGatt.GATT_SUCCESS) null else lastRead
    }

    suspend fun write(service: UUID, ch: UUID, value: ByteArray): Boolean = opLock.withLock {
        val c = gatt?.getService(service)?.getCharacteristic(ch) ?: return false
        val d = begin()
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = value
        if (gatt?.writeCharacteristic(c) != true) return false
        await(d) == BluetoothGatt.GATT_SUCCESS
    }

    /** Active notifications ou indications sur une caractéristique. */
    suspend fun subscribe(service: UUID, ch: UUID, indicate: Boolean = false): Boolean = opLock.withLock {
        val g = gatt ?: return false
        val c = g.getService(service)?.getCharacteristic(ch) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val desc = c.getDescriptor(Ftms.DESC_CCC) ?: return false
        val d = begin()
        desc.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(desc)) return false
        await(d) == BluetoothGatt.GATT_SUCCESS
    }

    fun close() {
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        pending?.complete(-1)
    }

    private fun begin() = CompletableDeferred<Int>().also { pending = it }

    private suspend fun await(d: CompletableDeferred<Int>): Int =
        runCatching { withTimeout(5_000) { d.await() } }.getOrDefault(-1)
}
