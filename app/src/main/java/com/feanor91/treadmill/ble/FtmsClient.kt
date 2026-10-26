package com.feanor91.treadmill.ble

import android.annotation.SuppressLint
import android.content.Context
import com.feanor91.treadmill.domain.BleDevice
import com.feanor91.treadmill.domain.ConnectionState
import com.feanor91.treadmill.domain.MachineLimits
import com.feanor91.treadmill.domain.TreadmillMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Client FTMS : connexion, lecture des limites, pilotage vitesse / inclinaison / start / stop. */
@SuppressLint("MissingPermission")
class FtmsClient(private val context: Context, private val scope: CoroutineScope) {

    private val scanner = BleScanner(context)

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _limits = MutableStateFlow(MachineLimits())
    val limits: StateFlow<MachineLimits> = _limits.asStateFlow()

    private val _metrics = MutableStateFlow(TreadmillMetrics())
    val metrics: StateFlow<TreadmillMetrics> = _metrics.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val bluetoothEnabled: Boolean get() = scanner.adapter?.isEnabled == true

    private var gatt: BleGatt? = null
    private var lastDevice: BleDevice? = null
    private var wantConnected = false
    private var reconnectJob: Job? = null
    private var silent = false

    private companion object {
        const val CP_NO_RESPONSE = -1
        const val CP_WRITE_FAILED = -2
        const val CP_NOT_PERMITTED = 0x05
    }

    private val cpLock = Mutex()
    @Volatile private var cpWaiter: CompletableDeferred<Int>? = null
    @Volatile private var cpOpcode = -1

    fun scan(): Flow<List<BleDevice>> = scanner.scan(Ftms.SERVICE_FTMS)

    fun clearError() { _error.value = null }

    /** [silent] : pas de message d'erreur en cas d'échec (tentatives automatiques, tapis éteint). */
    suspend fun connect(device: BleDevice, silent: Boolean = false): Boolean {
        wantConnected = true
        lastDevice = device
        this.silent = silent
        return doConnect(device)
    }

    private suspend fun doConnect(device: BleDevice): Boolean {
        gatt?.close()
        _error.value = null
        _state.value = ConnectionState.Connecting
        val adapter = scanner.adapter ?: return fail("Bluetooth indisponible")
        val g = BleGatt(context, ::onNotify, ::onClosed)
        gatt = g

        if (!g.connect(adapter.getRemoteDevice(device.address))) return fail("Connexion au tapis impossible")
        if (!g.hasCharacteristic(Ftms.SERVICE_FTMS, Ftms.CHAR_TREADMILL_DATA)) return fail("Cet appareil n'expose pas de tapis FTMS")

        // Limites physiques du tapis
        var limits = MachineLimits()
        g.read(Ftms.SERVICE_FTMS, Ftms.CHAR_SPEED_RANGE)?.let { limits = FtmsParser.parseSpeedRange(it, limits) }
        if (g.hasCharacteristic(Ftms.SERVICE_FTMS, Ftms.CHAR_INCLINE_RANGE)) {
            g.read(Ftms.SERVICE_FTMS, Ftms.CHAR_INCLINE_RANGE)?.let { limits = FtmsParser.parseInclineRange(it, limits) }
        } else {
            limits = limits.copy(supportsIncline = false, minInclinePct = 0.0, maxInclinePct = 0.0)
        }
        _limits.value = limits

        // Ordre important : indications du Control Point avant toute commande
        g.subscribe(Ftms.SERVICE_FTMS, Ftms.CHAR_CONTROL_POINT, indicate = true)
        if (g.hasCharacteristic(Ftms.SERVICE_FTMS, Ftms.CHAR_STATUS)) g.subscribe(Ftms.SERVICE_FTMS, Ftms.CHAR_STATUS)
        if (!g.subscribe(Ftms.SERVICE_FTMS, Ftms.CHAR_TREADMILL_DATA)) return fail("Flux de données du tapis indisponible")

        _deviceName.value = device.name
        if (!requestControl()) return fail("Le tapis refuse la prise de contrôle")
        _state.value = ConnectionState.Ready
        return true
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        reset()
    }

    // ---- Commandes ----

    suspend fun requestControl(): Boolean = command(FtmsParser.cmdRequestControl(), retryControl = false)
    suspend fun start(): Boolean = command(FtmsParser.cmdStart())
    suspend fun pause(): Boolean = command(FtmsParser.cmdPause())
    suspend fun stop(): Boolean = command(FtmsParser.cmdStop())

    /**
     * Arrêt d'urgence : on n'attend pas la réponse d'une commande en cours (qui bloquerait la file jusqu'à 3 s)
     * et on envoie Stop plusieurs fois, au cas où une écriture serait perdue.
     */
    suspend fun emergencyStop() {
        cpWaiter?.complete(CP_NO_RESPONSE)
        repeat(3) {
            send(FtmsParser.cmdStop())
            delay(300)
        }
    }

    suspend fun setSpeed(kmh: Double): Boolean =
        command(FtmsParser.cmdSetSpeed(_limits.value.clampSpeed(kmh)))

    suspend fun setIncline(pct: Double): Boolean {
        if (!_limits.value.supportsIncline) return true
        return command(FtmsParser.cmdSetIncline(_limits.value.clampIncline(pct)))
    }

    private suspend fun command(bytes: ByteArray, retryControl: Boolean = true): Boolean {
        var result = send(bytes)
        if (result == CP_NOT_PERMITTED && retryControl) {
            // Contrôle perdu (ex. tapis redémarré) : on le redemande une fois puis on rejoue.
            send(FtmsParser.cmdRequestControl())
            result = send(bytes)
        }
        // Certains tapis n'indiquent jamais : l'absence de réponse n'est pas une erreur.
        return result == CP_NO_RESPONSE || result == Ftms.RESULT_SUCCESS
    }

    /** Écrit une commande et attend l'indication du Control Point. Renvoie le code résultat. */
    private suspend fun send(bytes: ByteArray): Int = cpLock.withLock {
        val g = gatt ?: return CP_WRITE_FAILED
        val waiter = CompletableDeferred<Int>()
        cpWaiter = waiter
        cpOpcode = bytes[0].toInt() and 0xFF
        if (!g.write(Ftms.SERVICE_FTMS, Ftms.CHAR_CONTROL_POINT, bytes)) return CP_WRITE_FAILED
        val result = withTimeoutOrNull(3_000) { waiter.await() } ?: CP_NO_RESPONSE
        cpWaiter = null
        result
    }

    // ---- Callbacks GATT ----

    private fun onNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Ftms.CHAR_TREADMILL_DATA -> {
                val m = FtmsParser.parseTreadmillData(value)
                _metrics.update { old ->
                    old.copy(
                        speedKmh = m.speedKmh ?: old.speedKmh,
                        inclinePct = m.inclinePct ?: old.inclinePct,
                        distanceM = m.distanceM ?: old.distanceM,
                        heartRate = m.heartRate ?: old.heartRate,
                        energyKcal = m.energyKcal ?: old.energyKcal,
                        elapsedS = m.elapsedS ?: old.elapsedS,
                    )
                }
            }
            Ftms.CHAR_CONTROL_POINT -> {
                // [0x80, opcode demandé, code résultat]
                if (value.size >= 3 && (value[0].toInt() and 0xFF) == Ftms.OP_RESPONSE &&
                    (value[1].toInt() and 0xFF) == cpOpcode
                ) cpWaiter?.complete(value[2].toInt() and 0xFF)
            }
        }
    }

    private fun onClosed() {
        val wasReady = _state.value == ConnectionState.Ready
        reset()
        val device = lastDevice
        if (wantConnected && wasReady && device != null) startReconnect(device)
    }

    private fun startReconnect(device: BleDevice) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            repeat(10) {
                if (!wantConnected) return@launch
                delay(3_000)
                if (doConnect(device)) return@launch
            }
            _error.value = "Connexion perdue avec le tapis"
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun reset() {
        _state.value = ConnectionState.Disconnected
        _metrics.value = TreadmillMetrics()
        _deviceName.value = null
    }

    private fun fail(msg: String): Boolean {
        if (!silent) _error.value = msg
        gatt?.close()
        gatt = null
        wantConnected = false
        _state.value = ConnectionState.Disconnected
        return false
    }
}
