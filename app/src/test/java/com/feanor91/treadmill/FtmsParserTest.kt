package com.feanor91.treadmill

import com.feanor91.treadmill.ble.FtmsParser
import com.feanor91.treadmill.domain.MachineLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun treadmillData_speedInclinationDistanceHrTime() {
        // flags = speed(bit0=0) + distance(bit2) + inclination(bit3) + HR(bit8) + elapsed(bit10) = 0x050C
        val d = bytes(
            0x0C, 0x05,
            0x2C, 0x01,             // 3.00 km/h
            0x10, 0x27, 0x00,       // 10000 m
            0x32, 0x00, 0x00, 0x00, // 5.0 %, ramp angle 0
            0x8C,                   // 140 bpm
            0x3C, 0x00,             // 60 s
        )
        val m = FtmsParser.parseTreadmillData(d)
        assertEquals(3.0, m.speedKmh!!, 1e-9)
        assertEquals(10000, m.distanceM)
        assertEquals(5.0, m.inclinePct!!, 1e-9)
        assertEquals(140, m.heartRate)
        assertEquals(60, m.elapsedS)
    }

    @Test fun treadmillData_moreDataFlagMeansNoSpeed() {
        val m = FtmsParser.parseTreadmillData(bytes(0x01, 0x00))
        assertNull(m.speedKmh)
    }

    @Test fun treadmillData_skipsAverageSpeedAndEnergyBeforeHr() {
        // flags: speed + avg speed(bit1) + energy(bit7) + HR(bit8) = 0x0182
        val d = bytes(0x82, 0x01, 0xE8, 0x03, 0xF4, 0x01, 0x64, 0x00, 0x0A, 0x00, 0x01, 0x78)
        val m = FtmsParser.parseTreadmillData(d)
        assertEquals(10.0, m.speedKmh!!, 1e-9)
        assertEquals(100, m.energyKcal)
        assertEquals(120, m.heartRate)
    }

    @Test fun ranges() {
        val speed = FtmsParser.parseSpeedRange(bytes(0x64, 0x00, 0xD0, 0x07, 0x0A, 0x00), MachineLimits())
        assertEquals(1.0, speed.minSpeedKmh, 1e-9)
        assertEquals(20.0, speed.maxSpeedKmh, 1e-9)
        assertEquals(0.1, speed.speedStepKmh, 1e-9)
        assertTrue(speed.fromDevice)

        // min -2.0 %, max 15.0 %, pas 0.5 %
        val inc = FtmsParser.parseInclineRange(bytes(0xEC, 0xFF, 0x96, 0x00, 0x05, 0x00), speed)
        assertEquals(-2.0, inc.minInclinePct, 1e-9)
        assertEquals(15.0, inc.maxInclinePct, 1e-9)
        assertEquals(0.5, inc.inclineStepPct, 1e-9)
    }

    @Test fun heartRate_8and16bit() {
        assertEquals(72, FtmsParser.parseHeartRate(bytes(0x00, 72)))
        assertEquals(300, FtmsParser.parseHeartRate(bytes(0x01, 0x2C, 0x01)))
    }

    @Test fun commands() {
        assertArrayEquals(bytes(0x02, 0xE8, 0x03), FtmsParser.cmdSetSpeed(10.0))
        assertArrayEquals(bytes(0x03, 0x32, 0x00), FtmsParser.cmdSetIncline(5.0))
        assertArrayEquals(bytes(0x03, 0xF6, 0xFF), FtmsParser.cmdSetIncline(-1.0))
        assertArrayEquals(bytes(0x08, 0x02), FtmsParser.cmdPause())
    }

    @Test fun limitsClampAndSnap() {
        val l = MachineLimits(minSpeedKmh = 1.0, maxSpeedKmh = 18.0, speedStepKmh = 0.1, maxInclinePct = 12.0, inclineStepPct = 0.5)
        assertEquals(18.0, l.clampSpeed(25.0), 1e-9)
        assertEquals(1.0, l.clampSpeed(0.0), 1e-9)
        assertEquals(7.3, l.clampSpeed(7.31), 1e-9)
        assertEquals(12.0, l.clampIncline(20.0), 1e-9)
        assertEquals(3.5, l.clampIncline(3.4), 1e-9)
        assertEquals(0.0, l.copy(supportsIncline = false).clampIncline(5.0), 1e-9)
    }
}
