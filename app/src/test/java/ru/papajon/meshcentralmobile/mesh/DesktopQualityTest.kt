package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class DesktopQualityTest {
    @Test fun fpsIsConvertedToMillisecondsAndScaleIsPreserved() {
        val quality = DesktopQuality(80, 10, 512)
        assertEquals(100, quality.frameIntervalMillis)
        assertArrayEquals(byteArrayOf(0, 5, 0, 10, 1, 80, 2, 0, 0, 100), quality.packet())
    }
    @Test fun slowestWebIntervalIsNotClampedToTwoHundred() {
        assertArrayEquals(byteArrayOf(0, 5, 0, 10, 1, 60, 4, 0, 3, -24),
            KvmInputEncoder.compression(frameRate = 1000))
    }
    @Test fun initialisationKeepsRequestedScale() {
        assertArrayEquals(byteArrayOf(0, 5, 0, 10, 1, 60, 2, 0, 0, 100),
            KvmInputEncoder.initialisationSequence(frameRate = 100, scaling = 512).first())
    }
}
