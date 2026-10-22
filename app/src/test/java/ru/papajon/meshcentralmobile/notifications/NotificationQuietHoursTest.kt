package ru.papajon.meshcentralmobile.notifications

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationQuietHoursTest {
    private val zone = ZoneOffset.UTC
    private fun at(hour: Int): Long =
        LocalDate.of(2026, 1, 15).atTime(hour, 0).toInstant(zone).toEpochMilli()

    @Test
    fun crossMidnightWindowUsesLocalHour() {
        val quiet = QuietHours(enabled = true, startHour = 22, endHour = 7)
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(22), zone))
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(23), zone))
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(0), zone))
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(6), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet, at(7), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet, at(12), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet, at(21), zone))
    }

    @Test
    fun sameDayWindowAndDisabledSwitch() {
        val quiet = QuietHours(enabled = true, startHour = 9, endHour = 17)
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(9), zone))
        assertTrue(NotificationQuietHours.isQuiet(quiet, at(16), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet, at(17), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet.copy(enabled = false), at(12), zone))
        assertFalse(NotificationQuietHours.isQuiet(quiet.copy(startHour = 7, endHour = 7), at(7), zone))
    }

    @Test
    fun hourValidationIsInclusiveZeroToTwentyThree() {
        assertTrue(NotificationQuietHours.isValid(0, 23))
        assertFalse(NotificationQuietHours.isValid(-1, 7))
        assertFalse(NotificationQuietHours.isValid(22, 24))
    }
}
