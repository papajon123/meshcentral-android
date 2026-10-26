package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BiosDateTest {
    @Test fun agentCimDateUsesDisplayLocaleWithoutTimezoneShift() {
        val reply = JSONObject("""{"action":"getsysinfo","hardware":{"identifiers":{"bios_date":"20250409000000.000000+000"}}}""")
        val date = HardwareDetails.parse(reply).biosDate
        assertEquals("9 апр. 2025 г.", BiosDate.format(date, Locale.forLanguageTag("ru-RU"))?.replace('\u202f', ' '))
        assertEquals("Apr 9, 2025", BiosDate.format(date, Locale.US))
        assertEquals("Apr 9, 2025", BiosDate.format("20250409000000.000000+840", Locale.US))
    }

    @Test fun invalidPartialAndNonCimDatesAreNotInvented() {
        listOf("20250229000000.000000+000", "20250409250000.000000+000", "2025**********.******+***",
            "04/09/2025", "unknown", "", "20250409000000.000000:000").forEach {
            assertEquals(it, BiosDate.format(it, Locale.US))
        }
        assertNull(BiosDate.format(null, Locale.US))
    }
}
