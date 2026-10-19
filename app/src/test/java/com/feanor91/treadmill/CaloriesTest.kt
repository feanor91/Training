package com.feanor91.treadmill

import com.feanor91.treadmill.domain.Calories
import com.feanor91.treadmill.domain.Sex
import com.feanor91.treadmill.domain.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesTest {
    @Test fun walkingFlat_matchesAcsm() {
        // 4,5 km/h = 75 m/min -> 7,5 ml/kg/min nets ; 97 kg -> 3,6375 kcal/min
        assertEquals(3.6375, Calories.activeKcalPerMin(4.5, 0.0, 97.0), 1e-6)
    }

    @Test fun inclineAddsCost() {
        assertTrue(Calories.activeKcalPerMin(4.0, 3.0, 97.0) > Calories.activeKcalPerMin(4.0, 0.0, 97.0))
    }

    @Test fun zeroSpeedOrWeight_isZero() {
        assertEquals(0.0, Calories.activeKcalPerMin(0.0, 5.0, 97.0), 0.0)
        assertEquals(0.0, Calories.activeKcalPerMin(5.0, 0.0, 0.0), 0.0)
    }

    @Test fun continuousAcrossModelBoundaries() {
        val a = Calories.activeKcalPerMin(5.999, 0.0, 80.0)
        val b = Calories.activeKcalPerMin(6.001, 0.0, 80.0)
        assertEquals(a, b, 0.01)
        val c = Calories.activeKcalPerMin(7.999, 0.0, 80.0)
        val d = Calories.activeKcalPerMin(8.001, 0.0, 80.0)
        assertEquals(c, d, 0.01)
    }

    @Test fun mifflinStJeor() {
        // 10*97 + 6,25*172 - 5*62 + 5 = 970 + 1075 - 310 + 5
        assertEquals(1740.0, Calories.bmrPerDay(UserProfile(97.0, 172.0, 62, Sex.Male)), 1e-9)
        assertEquals(1574.0, Calories.bmrPerDay(UserProfile(97.0, 172.0, 62, Sex.Female)), 1e-9)
    }
}

class StepsTest {
    @Test fun walkingCadenceIsPlausible() {
        val perMin = Calories.stepsPerSecond(4.5, 172.0) * 60
        assertTrue("cadence $perMin", perMin in 95.0..115.0)
    }

    @Test fun fasterMeansMoreSteps() {
        assertTrue(Calories.stepsPerSecond(8.0, 172.0) > Calories.stepsPerSecond(4.0, 172.0))
    }

    @Test fun zeroWhenStopped() {
        assertEquals(0.0, Calories.stepsPerSecond(0.0, 172.0), 0.0)
    }
}
