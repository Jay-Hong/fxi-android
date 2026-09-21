package com.jay.fxi.data.entitlements.control

import androidx.datastore.preferences.core.*
import com.jay.fxi.data.entitlements.*
import kotlinx.serialization.json.*
import com.jay.fxi.data.entitlements.control.FloorGuardFixtures as F
import org.junit.Assert.*
import org.junit.Test

class FloorArithmeticBoundaryTest {
    private val base = FloorV1("boot", 10000, 30000, LifetimeId("life"))
    private val now = BootReading("boot", 11000)
    private fun invalid(id: String, floor: FloorV1, reading: BootReading) {
        // Six separate domains; exactly one violation, independently of remainingAt.
        val facts = listOf(floor.anchorBootId != "", floor.anchorElapsedMillis >= 0, floor.waitMillis >= 0,
            floor.originLifetimeId.value.isNotEmpty(), reading.bootId != "", reading.elapsedMillis >= 0)
        assertEquals(1, facts.count { !it })
        assertNull(F.eligible(id), floor.remainingAt(reading))
    }
    @Test fun F03a_nowBoot() = invalid("F03a_nowBoot", base, now.copy(bootId = ""))
    @Test fun F03a_anchorBoot() = invalid("F03a_anchorBoot", base.copy(anchorBootId = ""), now)
    @Test fun F03b_nowElapsed() = invalid("F03b_nowElapsed", base, now.copy(elapsedMillis = -1))
    @Test fun F03b_anchorElapsed() = invalid("F03b_anchorElapsed", base.copy(anchorElapsedMillis = -1), now)
    @Test fun F03c_wait() = invalid("F03c_wait", base.copy(waitMillis = -1), now)
    @Test fun F03c_origin() = invalid("F03c_origin", base.copy(originLifetimeId = LifetimeId("")), now)
    private fun remaining(id: String, floor: FloorV1, reading: BootReading, expected: Long) {
        assertTrue(floor.waitMillis >= 0 && floor.anchorElapsedMillis >= 0 && reading.elapsedMillis >= 0)
        assertTrue(floor.anchorBootId != "" && reading.bootId != "" && floor.originLifetimeId.value.isNotEmpty())
        assertEquals(F.atomic(id), expected, floor.remainingAt(reading))
    }
    @Test fun F08b_bothUnknown() = remaining("F08b_bothUnknown", base.copy(anchorBootId = null), now.copy(bootId = null), 30000L)
    @Test fun F08b_currentUnknown() = remaining("F08b_currentUnknown", base, now.copy(bootId = null), 30000L)
    @Test fun F08b_anchorUnknown() = remaining("F08b_anchorUnknown", base.copy(anchorBootId = null), now, 30000L)
    @Test fun F08c_different() = remaining("F08c_different", base, now.copy(bootId = "other"), 30000L)
    @Test fun F08d_backwards() = remaining("F08d_backwards", base, now.copy(elapsedMillis = 9999), 30000L)
    @Test fun F08a_zero() = remaining("F08a_zero", base, now.copy(elapsedMillis = 40000), 0L)
    @Test fun F03_waitOne() = remaining("F03_waitOne", base.copy(waitMillis = 1), now, 0L)
    @Test fun F03_waitMax() = remaining("F03_waitMax", base.copy(anchorElapsedMillis = 0, waitMillis = Long.MAX_VALUE), now.copy(elapsedMillis = Long.MAX_VALUE), 0L)
    @Test fun waitZeroUnknownStaysZero() = remaining("F03.zero", base.copy(waitMillis = 0, anchorBootId = null), now, 0)
    @Test fun sameBootPartialWait() = remaining("F03.partial", base, now, 29000)
    @Test fun originDoesNotGrantContinuity() = remaining("F08.origin", base.copy(originLifetimeId = F.origin), now.copy(bootId = null), 30000)

    @Test fun F03_clampUpper() {
        val floor = base.copy(waitMillis = 1)
        assertEquals("boot", floor.anchorBootId)
        assertEquals(floor.anchorBootId, now.bootId)
        assertEquals(1000L, now.elapsedMillis - floor.anchorElapsedMillis)
        assertEquals(1L, floor.waitMillis)
        // Consumption greater than wait must saturate at zero, never become negative.
        remaining("F03_clampUpper", floor, now, 0L)
    }

}
