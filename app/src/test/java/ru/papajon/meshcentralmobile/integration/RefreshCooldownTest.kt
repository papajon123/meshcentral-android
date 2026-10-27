package ru.papajon.meshcentralmobile.integration

import org.junit.Assert.*
import org.junit.Test

class RefreshCooldownTest {
    private val wall = 1_700_000_000_000L
    @Test fun rebootAfterHoursAllowsImmediately() {
        assertEquals(0L, RefreshCooldown.evaluate(wall, 500_000, wall + 7_200_000, 1000).remainingMillis)
    }
    @Test fun rebootWithinMinutePreservesOnlyRemainingInterval() {
        val decision = RefreshCooldown.evaluate(wall, 500_000, wall + 5000, 1000)
        assertEquals(55_000L, decision.remainingMillis)
        assertTrue(decision.reanchor)
        // Persisted reanchoring must allow the next attempt at the original deadline.
        val age = RefreshCooldown.MILLIS - decision.remainingMillis
        assertEquals(1L, RefreshCooldown.evaluate(wall + 5000 - age, 1000 - age,
            wall + 59_999, 55_999).seconds)
        assertEquals(0L, RefreshCooldown.evaluate(wall, -4000, wall + 60_000, 56_000).seconds)
    }
    @Test fun rebootAndWallRollbackStartOneNewMinute() {
        assertEquals(60L, RefreshCooldown.evaluate(wall, 500_000, wall - 1, 1000).seconds)
        assertEquals(0L, RefreshCooldown.evaluate(wall - 1, 1000, wall + 59_999, 61_000).seconds)
    }
    @Test fun wallRollbackWithoutRebootUsesMonotonicTime() {
        assertEquals(1L, RefreshCooldown.evaluate(wall, 1000, wall - 1000, 60_999).seconds)
        assertEquals(0L, RefreshCooldown.evaluate(wall, 1000, wall - 1000, 61_000).seconds)
    }
    @Test fun forwardWallCannotBypassUptimeFloor() {
        assertEquals(60L, RefreshCooldown.evaluate(wall, 1000, wall + 7_200_000, 1001).seconds)
    }
    @Test fun slowWallRetainsItsFloorWithoutResettingTheMinute() {
        assertEquals(55L, RefreshCooldown.evaluate(wall, 1000, wall + 5000, 61_000).seconds)
        assertEquals(0L, RefreshCooldown.evaluate(wall, 1000, wall + 60_000, 116_000).seconds)
    }
    @Test fun countdownRoundsUpAtMillisecondBoundary() {
        assertEquals(1L, RefreshCooldown.Decision(1).seconds)
        assertEquals(2L, RefreshCooldown.Decision(1001).seconds)
        assertEquals(0L, RefreshCooldown.Decision(0).seconds)
    }
}
