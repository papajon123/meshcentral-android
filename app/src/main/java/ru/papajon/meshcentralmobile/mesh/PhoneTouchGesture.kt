package ru.papajon.meshcentralmobile.mesh

import kotlinx.coroutines.delay
import ru.papajon.meshcentralmobile.input.NativeRemoteInputSink

enum class PhoneNavigationKey(val code: Int) {
    Back(27), Home(36), Recents(120), Notifications(121), QuickSettings(122)
}

data class PhoneTouchPoint(val x: Int, val y: Int, val elapsedMillis: Long)

/** Bounded completed single-finger gesture. Multitouch is discarded before any remote down. */
class PhoneTouchGesture(x: Int, y: Int) {
    private val points = mutableListOf(PhoneTouchPoint(x, y, 0))
    private var cancelled = false

    fun cancel() { cancelled = true }

    fun move(x: Int, y: Int, elapsedMillis: Long) {
        if (cancelled) return
        val elapsed = elapsedMillis.coerceIn(points.last().elapsedMillis, MAX_DURATION_MILLIS)
        if (elapsed - points.last().elapsedMillis >= SAMPLE_MILLIS && points.size < MAX_POINTS) {
            points += PhoneTouchPoint(x, y, elapsed)
        }
    }

    fun finish(x: Int, y: Int, elapsedMillis: Long): List<PhoneTouchPoint> {
        if (cancelled) return emptyList()
        return points.toList() + PhoneTouchPoint(x, y, elapsedMillis.coerceIn(points.last().elapsedMillis, MAX_DURATION_MILLIS))
    }

    companion object {
        const val SAMPLE_MILLIS = 40L // Agent's continued-stroke step.
        const val MAX_DURATION_MILLIS = 8_000L // Below the agent's 10-second held-pointer watchdog.
        private const val MAX_POINTS = 201

        /** Cancellation, screen disposal and capability changes always attempt to release a press. */
        suspend fun replay(points: List<PhoneTouchPoint>, input: NativeRemoteInputSink, allowed: () -> Boolean) {
            if (points.size < 2 || !allowed()) return
            var last = points.first()
            if (!input.sendMouseButton(last.x, last.y, MouseButton.Left, true)) return
            try {
                for (next in points.drop(1)) {
                    delay((next.elapsedMillis - last.elapsedMillis).coerceAtLeast(0))
                    if (!allowed()) return
                    if ((next.x != last.x || next.y != last.y) && !input.sendMouseMove(next.x, next.y)) return
                    last = next
                }
            } finally {
                input.sendMouseButton(last.x, last.y, MouseButton.Left, false)
            }
        }
    }
}
