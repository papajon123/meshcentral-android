package ru.papajon.meshcentralmobile.ui.screens.session

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SessionMetricsTest {
    @Test fun usesCorrectDecimalByteUnitsAtBoundaries() {
        assertEquals("0 B/s", sessionTrafficRate(-1))
        assertEquals("999 B/s", sessionTrafficRate(999))
        assertEquals("1.0 KB/s", sessionTrafficRate(1000))
        assertEquals("250.0 KB/s", sessionTrafficRate(250000))
        assertEquals("1.0 MB/s", sessionTrafficRate(1000000))
        assertEquals("1.0 GB/s", sessionTrafficRate(1000000000))
    }
    @Test fun metricsStayLatinAndFiniteInAnyLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            assertEquals("12.5 fps · 1.5 KB/s", sessionStreamRate(12.5f, 1500))
            assertEquals("0.0 fps · 0 B/s", sessionStreamRate(Float.NaN, -1))
            assertFalse(sessionTrafficRate(Long.MAX_VALUE).contains("Infinity"))
        } finally { Locale.setDefault(previous) }
    }
}
