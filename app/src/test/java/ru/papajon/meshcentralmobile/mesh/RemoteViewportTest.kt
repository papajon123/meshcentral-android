package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch-to-remote coordinate mapping. A wrong mapping makes the remote cursor land somewhere the
 * user did not tap, which is the kind of defect that only shows up on a real device.
 */
class RemoteViewportTest {
    @Test
    fun fitsRemoteScreenIntoCanvasKeepingAspectRatio() {
        val viewport = RemoteViewport(canvasWidth = 1000, canvasHeight = 1000, remoteWidth = 1920, remoteHeight = 1080)

        assertEquals(1000f / 1920f, viewport.fitScale, 0.0001f)
        assertEquals(1000f, viewport.drawnWidth, 0.01f)
        assertEquals(562.5f, viewport.drawnHeight, 0.01f)
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(218.75f, viewport.top, 0.01f)
    }

    @Test
    fun mapsCanvasCornersToRemotePixels() {
        val viewport = RemoteViewport(canvasWidth = 1000, canvasHeight = 1000, remoteWidth = 1920, remoteHeight = 1080)

        assertEquals(0 to 0, viewport.toRemote(0f, viewport.top))
        assertEquals(0 to 0, viewport.toRemote(0.4f, viewport.top + 0.4f))
        assertEquals(
            1919 to 1079,
            viewport.toRemote(viewport.left + viewport.drawnWidth - 0.5f, viewport.top + viewport.drawnHeight - 0.5f),
        )
    }

    @Test
    fun mapsRemoteCentreBackToCanvas() {
        val viewport = RemoteViewport(canvasWidth = 800, canvasHeight = 600, remoteWidth = 1600, remoteHeight = 900)
        val (x, y) = viewport.toCanvas(800, 450)

        assertEquals(400f, x, 0.01f)
        assertEquals(300f, y, 0.01f)
        assertEquals(800 to 450, viewport.toRemote(x, y))
    }

    @Test
    fun zoomKeepsCentreAndClampsPanToImageBounds() {
        val viewport = RemoteViewport(
            canvasWidth = 1000,
            canvasHeight = 1000,
            remoteWidth = 1920,
            remoteHeight = 1080,
            zoom = 2f,
        )

        assertEquals(2000f, viewport.drawnWidth, 0.01f)
        assertEquals(-500f, viewport.left, 0.01f)

        val (clampedX, clampedY) = viewport.clampPan(10_000f, -10_000f)
        // Horizontal overflow is larger than the free-pan slack, vertical is smaller, so the
        // vertical limit is the slack rather than half the image.
        assertEquals(500f, clampedX, 0.01f)
        assertEquals(-1000f * RemoteViewport.FREE_PAN_FRACTION, clampedY, 0.01f)
    }

    @Test
    fun tapOutsideImageIsClampedToFramebuffer() {
        val viewport = RemoteViewport(canvasWidth = 1000, canvasHeight = 1000, remoteWidth = 640, remoteHeight = 480)

        val (x, y) = viewport.toRemote(-50f, -50f)

        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun missingFrameSizeIsHandledWithoutCrash() {
        val viewport = RemoteViewport(canvasWidth = 0, canvasHeight = 0, remoteWidth = 0, remoteHeight = 0)

        assertTrue(!viewport.remoteValid)
        assertEquals(0 to 0, viewport.toRemote(10f, 10f))
        assertEquals(1f, viewport.fitScale, 0f)
    }

    @Test
    fun zoomAroundKeepsTheTouchedRemotePixelUnderTheFinger() {
        val base = RemoteViewport(canvasWidth = 1000, canvasHeight = 800, remoteWidth = 5120, remoteHeight = 1440)
        val focusX = 700f
        val focusY = 420f
        val remoteBefore = base.toRemote(focusX, focusY)

        for (requestedZoom in listOf(2.5f, 12f, 16f)) {
            val zoomed = base.zoomAround(focusX, focusY, requestedZoom)
            val after = base.copy(zoom = zoomed.zoom, panX = zoomed.panX, panY = zoomed.panY)
            val remoteAfter = after.toRemote(focusX, focusY)

            assertEquals(requestedZoom, zoomed.zoom, 0.001f)
            assertTrue("remote x drifted: ${remoteBefore.first} -> ${remoteAfter.first}", kotlin.math.abs(remoteBefore.first - remoteAfter.first) <= 2)
            assertTrue("remote y drifted: ${remoteBefore.second} -> ${remoteAfter.second}", kotlin.math.abs(remoteBefore.second - remoteAfter.second) <= 2)
        }
    }

    @Test
    fun zoomIsClampedToTheAllowedRange() {
        val base = RemoteViewport(canvasWidth = 1000, canvasHeight = 800, remoteWidth = 1920, remoteHeight = 1080)

        assertEquals(0f, base.zoomAround(500f, 400f, 0.1f).zoom - 1f, 0.001f)
        assertEquals(16f, base.zoomAround(500f, 400f, 99f).zoom, 0.001f)
    }

    @Test
    fun panIsClampedToTheLargerOfOverflowAndFreePanSlack() {
        val base = RemoteViewport(canvasWidth = 1000, canvasHeight = 800, remoteWidth = 1920, remoteHeight = 1080, zoom = 2f)

        val (x, y) = base.clampPan(5_000f, 5_000f)
        val expectedX = maxOf((base.drawnWidth - 1000f) / 2f, 1000f * RemoteViewport.FREE_PAN_FRACTION)
        val expectedY = maxOf((base.drawnHeight - 800f) / 2f, 800f * RemoteViewport.FREE_PAN_FRACTION)

        assertEquals(expectedX, x, 0.01f)
        assertEquals(expectedY, y, 0.01f)
    }

    @Test
    fun aFittingDesktopCanStillBeMovedButStaysReachable() {
        // 5120x1440 on a tall phone: fit zoom shows the whole desktop, and dragging must still do
        // something, otherwise the user cannot move around at all.
        val base = RemoteViewport(canvasWidth = 1080, canvasHeight = 2337, remoteWidth = 5120, remoteHeight = 1440)

        val (x, y) = base.clampPan(10_000f, -10_000f)

        assertEquals(1080f * RemoteViewport.FREE_PAN_FRACTION, x, 0.01f)
        assertEquals(-2337f * RemoteViewport.FREE_PAN_FRACTION, y, 0.01f)
        assertTrue(kotlin.math.abs(x) < base.drawnWidth)
    }

    @Test
    fun mappingFollowsTheViewportWhenZoomedAndPanned() {
        val viewport = RemoteViewport(
            canvasWidth = 1000,
            canvasHeight = 800,
            remoteWidth = 1920,
            remoteHeight = 1080,
            zoom = 2f,
            panX = 100f,
            panY = -50f,
        )

        val (canvasX, canvasY) = viewport.toCanvas(960, 540)
        assertEquals(960 to 540, viewport.toRemote(canvasX, canvasY))
    }

    @Test
    fun tapsInTheLetterboxAreRejected() {
        // 5120x1440 on a tall phone leaves large black bands; a tap there must not click the
        // nearest framebuffer edge.
        val viewport = RemoteViewport(canvasWidth = 1080, canvasHeight = 2337, remoteWidth = 5120, remoteHeight = 1440)

        assertTrue(viewport.hitsImage(540f, viewport.top + 10f))
        assertTrue(viewport.hitsImage(540f, viewport.top + viewport.drawnHeight - 10f))
        assertFalse(viewport.hitsImage(540f, viewport.top - 40f))
        assertFalse(viewport.hitsImage(540f, viewport.top + viewport.drawnHeight + 40f))
    }
}
