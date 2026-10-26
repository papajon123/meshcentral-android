package ru.papajon.meshcentralmobile.mesh

import kotlin.math.min

/**
 * Maps between screen coordinates inside the remote desktop canvas and remote framebuffer pixels.
 *
 * Kept free of Android types so the mapping can be unit tested: wrong maths here means the remote
 * mouse lands somewhere other than where the user tapped.
 */
data class RemoteViewport(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val remoteWidth: Int,
    val remoteHeight: Int,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val fitWidth: Boolean = false,
) {
    val remoteValid: Boolean get() = remoteWidth > 0 && remoteHeight > 0
    val canvasValid: Boolean get() = canvasWidth > 0 && canvasHeight > 0

    /** Scale that fits the whole remote screen into the canvas. */
    val fitScale: Float
        get() {
            if (!remoteValid || !canvasValid) return 1f
            if (fitWidth && remoteHeight > remoteWidth) return canvasWidth.toFloat() / remoteWidth
            return min(canvasWidth.toFloat() / remoteWidth, canvasHeight.toFloat() / remoteHeight)
        }

    val scale: Float get() = fitScale * zoom

    val drawnWidth: Float get() = remoteWidth * scale
    val drawnHeight: Float get() = remoteHeight * scale

    val left: Float get() = (canvasWidth - drawnWidth) / 2f + panX
    val top: Float get() = (canvasHeight - drawnHeight) / 2f + panY

    /** Converts a touch position inside the canvas to absolute remote framebuffer pixels. */
    fun toRemote(x: Float, y: Float): Pair<Int, Int> {
        if (!remoteValid || !canvasValid) return 0 to 0
        val scaleValue = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val remoteX = ((x - left) / scaleValue).toInt().coerceIn(0, remoteWidth - 1)
        val remoteY = ((y - top) / scaleValue).toInt().coerceIn(0, remoteHeight - 1)
        return remoteX to remoteY
    }

    /** Converts a remote framebuffer point back into canvas coordinates. */
    fun toCanvas(remoteX: Int, remoteY: Int): Pair<Float, Float> {
        if (!remoteValid || !canvasValid) return 0f to 0f
        val scaleValue = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (left + remoteX * scaleValue) to (top + remoteY * scaleValue)
    }

    /**
     * Keeps the visible area inside the canvas when zoomed, and still allows a bounded free pan when
     * the whole framebuffer already fits.
     *
     * Without that slack, dragging does nothing at fit zoom (the picture is not larger than the
     * screen), which reads as "I cannot move around the screen". The desktop can be parked anywhere
     * inside [FREE_PAN_FRACTION] of the canvas and "Fit" recentres it.
     */
    fun clampPan(newPanX: Float, newPanY: Float): Pair<Float, Float> {
        val maxX = ((drawnWidth - canvasWidth) / 2f).coerceAtLeast(canvasWidth * FREE_PAN_FRACTION)
        val maxY = ((drawnHeight - canvasHeight) / 2f).coerceAtLeast(canvasHeight * FREE_PAN_FRACTION)
        return newPanX.coerceIn(-maxX, maxX) to newPanY.coerceIn(-maxY, maxY)
    }

    /**
     * Zooms while keeping the point under the fingers still, which is what makes pinch-zoom feel
     * attached to the content instead of jumping.
     *
     * Returns the new zoom and the pan that keeps [focusX],[focusY] (canvas coordinates) over the
     * same remote pixel.
     */
    fun zoomAround(focusX: Float, focusY: Float, requestedZoom: Float): ZoomResult {
        if (!remoteValid || !canvasValid) return ZoomResult(zoom, panX, panY)
        val newZoom = requestedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return ZoomResult(zoom, panX, panY)
        val newScale = fitScale * newZoom
        if (!newScale.isFinite() || newScale <= 0f) return ZoomResult(zoom, panX, panY)

        // Remote pixel currently under the focus point.
        val currentScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val remoteX = (focusX - left) / currentScale
        val remoteY = (focusY - top) / currentScale

        // Centre formula of `left`, solved for the pan that keeps the same remote pixel in place.
        val baseLeft = (canvasWidth - remoteWidth * newScale) / 2f
        val baseTop = (canvasHeight - remoteHeight * newScale) / 2f
        val newPanX = focusX - remoteX * newScale - baseLeft
        val newPanY = focusY - remoteY * newScale - baseTop
        val clamped = copy(zoom = newZoom).clampPan(newPanX, newPanY)
        val clampedX = clamped.first
        val clampedY = clamped.second
        return ZoomResult(newZoom, clampedX, clampedY)
    }

    /** Percentage of the remote width that is currently visible, for the zoom indicator. */
    val visibleRemoteFraction: Float
        get() {
            if (!remoteValid || !canvasValid) return 1f
            val scaleValue = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
            return (canvasWidth / (remoteWidth * scaleValue)).coerceIn(0f, 1f)
        }

    /**
     * True when a canvas point actually covers the framebuffer image.
     *
     * Clicks are only sent for such points: without this check a tap in the black letterbox area
     * would clamp to the nearest framebuffer edge and click whatever sits there (the taskbar, for
     * example).
     */
    fun hitsImage(x: Float, y: Float): Boolean {
        if (!remoteValid || !canvasValid) return false
        return x >= left && x <= left + drawnWidth && y >= top && y <= top + drawnHeight
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 16f

        /** How far the desktop may be parked inside the canvas when it already fits. */
        const val FREE_PAN_FRACTION = 0.35f
    }
}

data class ZoomResult(val zoom: Float, val panX: Float, val panY: Float)
