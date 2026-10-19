package com.feanor91.treadmill.ble

import com.feanor91.treadmill.domain.MachineLimits
import com.feanor91.treadmill.domain.TreadmillMetrics
import java.util.UUID

/** UUID et opcodes du Fitness Machine Service (Bluetooth SIG, FTMS 1.0). */
object Ftms {
    private fun u16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    val SERVICE_FTMS = u16(0x1826)
    val SERVICE_HEART_RATE = u16(0x180D)

    val CHAR_FEATURE = u16(0x2ACC)
    val CHAR_TREADMILL_DATA = u16(0x2ACD)
    val CHAR_SPEED_RANGE = u16(0x2AD4)
    val CHAR_INCLINE_RANGE = u16(0x2AD5)
    val CHAR_CONTROL_POINT = u16(0x2AD9)
    val CHAR_STATUS = u16(0x2ADA)
    val CHAR_HEART_RATE = u16(0x2A37)
    val DESC_CCC = u16(0x2902)

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_SPEED = 0x02
    const val OP_SET_INCLINE = 0x03
    const val OP_START = 0x07
    const val OP_STOP = 0x08
    const val OP_RESPONSE = 0x80
    const val RESULT_SUCCESS = 0x01
}

/** Décodage pur (sans dépendance Android) des trames FTMS, testable en JVM. */
object FtmsParser {

    private class Reader(val b: ByteArray, var pos: Int = 0) {
        fun has(n: Int) = pos + n <= b.size
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int { val v = u8() or (u8() shl 8); return v }
        fun s16(): Int = u16().toShort().toInt()
        fun u24(): Int { val v = u8() or (u8() shl 8) or (u8() shl 16); return v }
        fun skip(n: Int) { pos += n }
    }

    /** Treadmill Data (0x2ACD). Les champs sont dans l'ordre des bits de flags. */
    fun parseTreadmillData(data: ByteArray): TreadmillMetrics {
        if (data.size < 2) return TreadmillMetrics()
        val r = Reader(data)
        val flags = r.u16()
        var speed: Double? = null
        var incline: Double? = null
        var distance: Int? = null
        var hr: Int? = null
        var kcal: Int? = null
        var elapsed: Int? = null

        // bit 0 inversé : 0 => vitesse instantanée présente
        if ((flags and 0x0001) == 0 && r.has(2)) speed = r.u16() * 0.01
        if ((flags and 0x0002) != 0 && r.has(2)) r.skip(2)                       // vitesse moyenne
        if ((flags and 0x0004) != 0 && r.has(3)) distance = r.u24()              // m
        if ((flags and 0x0008) != 0 && r.has(4)) { incline = r.s16() * 0.1; r.skip(2) } // inclinaison + angle de rampe
        if ((flags and 0x0010) != 0 && r.has(4)) r.skip(4)                       // dénivelé +/-
        if ((flags and 0x0020) != 0 && r.has(1)) r.skip(1)                       // allure instantanée
        if ((flags and 0x0040) != 0 && r.has(1)) r.skip(1)                       // allure moyenne
        if ((flags and 0x0080) != 0 && r.has(5)) { kcal = r.u16(); r.skip(3) }  // total, /h, /min
        if ((flags and 0x0100) != 0 && r.has(1)) { val v = r.u8(); if (v in 1..254) hr = v }
        if ((flags and 0x0200) != 0 && r.has(1)) r.skip(1)                       // MET
        if ((flags and 0x0400) != 0 && r.has(2)) elapsed = r.u16()               // s
        return TreadmillMetrics(speed, incline, distance, hr, kcal, elapsed)
    }

    /** Supported Speed Range (0x2AD4) : min, max, pas — uint16, 0,01 km/h. */
    fun parseSpeedRange(data: ByteArray, base: MachineLimits): MachineLimits {
        if (data.size < 6) return base
        val r = Reader(data)
        val min = r.u16() * 0.01
        val max = r.u16() * 0.01
        val step = r.u16() * 0.01
        return base.copy(minSpeedKmh = min, maxSpeedKmh = max, speedStepKmh = if (step > 0) step else base.speedStepKmh, fromDevice = true)
    }

    /** Supported Inclination Range (0x2AD5) : min, max (sint16), pas (uint16) — 0,1 %. */
    fun parseInclineRange(data: ByteArray, base: MachineLimits): MachineLimits {
        if (data.size < 6) return base
        val r = Reader(data)
        val min = r.s16() * 0.1
        val max = r.s16() * 0.1
        val step = r.u16() * 0.1
        return base.copy(
            minInclinePct = min,
            maxInclinePct = max,
            inclineStepPct = if (step > 0) step else base.inclineStepPct,
            supportsIncline = max > min,
            fromDevice = true,
        )
    }

    /** Heart Rate Measurement (0x2A37). */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.size < 2) return null
        val wide = (data[0].toInt() and 0x01) != 0
        return if (wide && data.size >= 3) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }.takeIf { it > 0 }
    }

    // ---- Encodage des commandes du Control Point ----

    fun cmdRequestControl() = byteArrayOf(Ftms.OP_REQUEST_CONTROL.toByte())
    fun cmdStart() = byteArrayOf(Ftms.OP_START.toByte())
    fun cmdStop() = byteArrayOf(Ftms.OP_STOP.toByte(), 0x01)
    fun cmdPause() = byteArrayOf(Ftms.OP_STOP.toByte(), 0x02)

    fun cmdSetSpeed(kmh: Double): ByteArray {
        val v = Math.round(kmh * 100).toInt().coerceIn(0, 0xFFFF)
        return byteArrayOf(Ftms.OP_SET_SPEED.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    fun cmdSetIncline(pct: Double): ByteArray {
        val v = Math.round(pct * 10).toInt().coerceIn(-32768, 32767)
        return byteArrayOf(Ftms.OP_SET_INCLINE.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }
}
