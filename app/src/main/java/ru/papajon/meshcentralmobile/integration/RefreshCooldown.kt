package ru.papajon.meshcentralmobile.integration

/** Persisted rate-limit clock policy, independent of Android and process lifetime. */
internal object RefreshCooldown {
    const val MILLIS = 60_000L
    data class Decision(val remainingMillis: Long, val reanchor: Boolean = false) {
        val seconds: Long get() = (remainingMillis + 999) / 1000
    }
    fun evaluate(lastWall: Long, lastElapsed: Long, wall: Long, elapsed: Long): Decision {
        if (elapsed < lastElapsed) {
            // A reboot invalidates uptime, but a forward wall clock retains the elapsed interval.
            val age = if (wall < lastWall) 0 else (wall - lastWall).coerceAtMost(MILLIS)
            return Decision(MILLIS - age, reanchor = true)
        }
        val monotonic = (MILLIS - (elapsed - lastElapsed)).coerceAtLeast(0)
        val wallFloor = if (wall >= lastWall) (MILLIS - (wall - lastWall)).coerceAtLeast(0) else 0
        return Decision(maxOf(monotonic, wallFloor))
    }
}
