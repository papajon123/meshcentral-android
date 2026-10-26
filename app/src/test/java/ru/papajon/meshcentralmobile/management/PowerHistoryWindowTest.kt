package ru.papajon.meshcentralmobile.management

import org.junit.Assert.*
import org.junit.Test

class PowerHistoryWindowTest {
    @Test fun clipsToSevenDaysAndKeepsUnobservedPastUnknown() {
        val now = 10 * PowerHistoryWindow.DAY
        val result = PowerHistoryWindow.intervals(listOf(PowerTransition(5 * PowerHistoryWindow.DAY, 0, 1), PowerTransition(9 * PowerHistoryWindow.DAY, 1, 6)), now, java.time.ZoneId.of("UTC"))
        assertEquals(3, result.size)
        assertEquals(4 * PowerHistoryWindow.DAY, result[0].startMillis)
        assertNull(result[0].power)
        assertEquals(1, result[1].power)
        assertEquals(now, result.last().endMillis)
    }
    @Test fun earlierStateCarriesIntoWindowButFutureDoesNot() {
        val now = 10 * PowerHistoryWindow.DAY
        val result = PowerHistoryWindow.intervals(listOf(PowerTransition(PowerHistoryWindow.DAY, 0, 4), PowerTransition(now + 1, 4, 1)), now, java.time.ZoneId.of("UTC"))
        assertEquals(listOf(PowerInterval(4 * PowerHistoryWindow.DAY, now, 4)), result)
        assertNull(PowerHistoryWindow.intervals(emptyList(), now, java.time.ZoneId.of("UTC")).single().power)
    }
    @Test fun fallbackCurrentStateDoesNotInventSevenDaysOfHistory() {
        val now = 10 * PowerHistoryWindow.DAY
        assertNull(PowerHistoryWindow.intervals(listOf(PowerTransition(now, 1, 1)), now, java.time.ZoneId.of("UTC")).single().power)
    }
}
