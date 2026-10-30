package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class TrackpadMotionTest {
    private val viewport = RemoteViewport(400, 400, 400, 400, zoom = 2f)

    @Test fun followsAllEdgesAndKeepsPointerVisible() {
        for ((dx, dy) in listOf(1000f to 0f, -1000f to 0f, 0f to 1000f, 0f to -1000f)) {
            val result = viewport.moveTrackpad(200f, 200f, dx, dy)
            val view = viewport.copy(panX = result.panX, panY = result.panY)
            val point = view.toCanvas(result.x.toInt(), result.y.toInt())
            assertTrue(point.first in 0f..400f && point.second in 0f..400f)
            assertTrue(result.x in 0f..399f && result.y in 0f..399f)
            assertTrue(view.left <= 0f && view.top <= 0f)
            assertTrue(view.left + view.drawnWidth >= 400f && view.top + view.drawnHeight >= 400f)
        }
    }

    @Test fun centralMovementPreservesManualFreePan() {
        val result = viewport.copy(zoom = 1.2f, panX = 140f).moveTrackpad(200f, 200f, 1f, 0f)
        assertEquals(140f, result.panX, 0f)
    }

    @Test fun centralMovementDoesNotPan() {
        val result = viewport.moveTrackpad(200f, 200f, 10f, 10f)
        assertEquals(0f, result.panX, 0f)
        assertEquals(0f, result.panY, 0f)
    }

    @Test fun subpixelMovementsAccumulate() {
        var x = 200f
        repeat(32) { x = viewport.copy(zoom = 16f).moveTrackpad(x, 200f, 1f, 0f).x }
        assertEquals(202f, x, 0.001f)
    }

    @Test fun fittedLandscapeDoesNotPanIntoLetterbox() {
        val result = RemoteViewport(400, 800, 1920, 1080).moveTrackpad(960f, 540f, 10000f, 10000f)
        assertEquals(0f, result.panX, 0f)
        assertEquals(0f, result.panY, 0f)
    }

    @Test fun invalidViewportLeavesStateAlone() {
        assertEquals(TrackpadMotion(10f, 20f, 0f, 0f), RemoteViewport(0, 0, 0, 0).moveTrackpad(10f, 20f, 5f, 5f))
    }
}
