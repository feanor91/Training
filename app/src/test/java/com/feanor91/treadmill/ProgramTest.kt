package com.feanor91.treadmill

import com.feanor91.treadmill.domain.HeartZones
import com.feanor91.treadmill.domain.ProgramGenerator
import com.feanor91.treadmill.domain.ProgramGoal
import com.feanor91.treadmill.domain.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Test

class ProgramTest {
    private val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val start = LocalDate.of(2026, 10, 19) // lundi

    @Test fun twentyEightDaysFourWeekdays_gives16Sessions() {
        assertEquals(16, ProgramGenerator.trainingDates(start, 28, days).size)
    }

    @Test fun speedsNeverExceedMax_andWarmupCooldownAreGentle() {
        for (goal in ProgramGoal.entries) {
            val sessions = ProgramGenerator.generate(goal, start, 28, days, maxSpeed = 5.0)
            assertEquals(16, sessions.size)
            for (s in sessions) {
                assertTrue(s.steps.all { it.speedKmh <= 5.0 && (it.maxSpeedKmh ?: 0.0) <= 5.0 })
                assertEquals(2.5, s.steps.first().speedKmh, 1e-9)
                assertEquals(2.0, s.steps.last().speedKmh, 1e-9)
                assertTrue(s.totalSec >= 15 * 60)
            }
        }
    }

    @Test fun durationGrowsAcrossTheProgram() {
        val s = ProgramGenerator.generate(ProgramGoal.WEIGHT_LOSS, start, 28, days, 5.0)
        assertTrue(s.last().totalSec > s.first().totalSec)
    }

    @Test fun zoneStepsAreOptionalAndBounded() {
        val s = ProgramGenerator.generate(ProgramGoal.WEIGHT_LOSS, start, 28, days, 5.0)
        val zoned = s.flatMap { it.steps }.filter { it.targetZone != null }
        assertTrue(zoned.isNotEmpty())
        assertTrue(zoned.all { (it.minSpeedKmh ?: 0.0) <= it.speedKmh && it.speedKmh <= (it.maxSpeedKmh ?: 99.0) })
    }

    @Test fun heartZonesForSixtyTwo() {
        val max = HeartZones.maxHr(UserProfile(age = 62))
        assertEquals(165, max) // 208 - 0,7 * 62 = 164,6
        assertEquals(0, HeartZones.zoneOf(70, max))
        assertEquals(2, HeartZones.zoneOf(105, max))
        assertEquals(3, HeartZones.zoneOf(125, max))
        assertEquals(5, HeartZones.zoneOf(160, max))
        assertEquals(83..99, HeartZones.range(1, max)) // zone 1 = 50-60 %
        assertEquals(99..115, HeartZones.range(2, max)) // zone 2 = 60-70 %
    }
}
