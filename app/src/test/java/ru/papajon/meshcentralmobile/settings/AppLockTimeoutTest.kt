package ru.papajon.meshcentralmobile.settings

import org.junit.Assert.*
import org.junit.Test

class AppLockTimeoutTest {
    @Test fun acceptsCustomTimesAndBothBounds() {
        listOf(0L, 1L, 15L, 45L, 90L, 3600L, 86_400L).forEach {
            assertEquals(it, AppLockTimeout.parse(it.toString()))
        }
    }
    @Test fun rejectsInvalidValuesInsteadOfSilentlyChangingThem() {
        listOf("", " ", "-1", "+1", "1.5", "1e3", "86401", "9999999999999999999999", "12x").forEach {
            assertNull(it, AppLockTimeout.parse(it))
        }
    }
}
