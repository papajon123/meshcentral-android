package ru.papajon.meshcentralmobile.integration

import android.content.Context
import android.os.SystemClock

/** Persist before attempting authentication: process death must not reset the rate limit. */
class WidgetRefreshGate(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_auth_gate", Context.MODE_PRIVATE)

    enum class Claim { ALLOWED, COOLDOWN, BLOCKED, STORAGE_FAILURE }

    /** Boolean API kept for existing callers; [typedClaim] distinguishes cooldown and storage. */
    fun claim(profileId: String, wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        typedClaim(profileId, wall, elapsed) == Claim.ALLOWED

    /** Reboots use the wall interval; only a simultaneous rollback starts a fresh minute. */
    fun typedClaim(profileId: String, wall: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime()): Claim = synchronized(lock) {
        if (prefs.getBoolean("$profileId.blocked", false)) return@synchronized Claim.BLOCKED
        if (prefs.contains("$profileId.wall")) {
            val decision = cooldown(profileId, wall, elapsed)
            if (decision.remainingMillis > 0) {
                if (decision.reanchor) {
                    val age = RefreshCooldown.MILLIS - decision.remainingMillis
                    if (!write(profileId, wall - age, elapsed - age)) return@synchronized Claim.STORAGE_FAILURE
                }
                return@synchronized Claim.COOLDOWN
            }
        }
        if (write(profileId, wall, elapsed)) Claim.ALLOWED else Claim.STORAGE_FAILURE
    }

    fun remainingSeconds(profileId: String, wall: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime()): Long = synchronized(lock) {
        if (!prefs.contains("$profileId.wall")) 0 else cooldown(profileId, wall, elapsed).seconds
    }

    private fun cooldown(profileId: String, wall: Long, elapsed: Long) = RefreshCooldown.evaluate(
        prefs.getLong("$profileId.wall", 0), prefs.getLong("$profileId.elapsed", 0), wall, elapsed)

    fun reject(profileId: String) = synchronized(lock) {
        prefs.edit().putBoolean("$profileId.blocked", true).commit()
    }

    /** Only a successful foreground app login may remove the rejection latch. */
    fun appAuthenticated(profileId: String) = synchronized(lock) {
        prefs.edit().remove("$profileId.blocked").commit()
    }

    private fun write(profileId: String, wall: Long, elapsed: Long): Boolean =
        prefs.edit().putLong("$profileId.wall", wall).putLong("$profileId.elapsed", elapsed).commit()

    companion object {
        private val lock = Any()
    }
}
