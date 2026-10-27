package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class DesktopStreamMeterTest {
    @Test fun ratesUseElapsedTimeAndFallToZeroOnIdle() {
        val meter = DesktopStreamMeter()
        meter.reset(1000)
        meter.received(1000); meter.received(2000); meter.frame(); meter.frame()
        assertEquals(DesktopStreamMeter.Rate(1f, 1500, 3000), meter.sample(3000))
        assertEquals(DesktopStreamMeter.Rate(0f, 0, 3000), meter.sample(4000))
        meter.reset(5000)
        assertEquals(DesktopStreamMeter.Rate(0f, 0, 0), meter.sample(6000))
    }
}
