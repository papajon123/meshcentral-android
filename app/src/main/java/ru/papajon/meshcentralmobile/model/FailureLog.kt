package ru.papajon.meshcentralmobile.model

import android.util.Log

/**
 * Records that a handled failure happened without leaking its details.
 *
 * Exception messages can carry relay URLs with authentication cookies, server addresses, device
 * names or file paths, so only exception class names (and their causes) are written. This keeps
 * otherwise silent fallbacks diagnosable from `adb logcat` while preserving the app's rule that
 * raw error text never reaches the system log.
 */
object FailureLog {
    private const val MAX_CAUSES = 4

    fun record(tag: String, operation: String, failure: Throwable) {
        try {
            Log.w(tag, "$operation failed: ${describe(failure)}")
        } catch (_: RuntimeException) {
            // android.util.Log is unavailable in plain JVM unit tests.
        }
    }

    fun record(tag: String, operation: String) {
        try {
            Log.w(tag, "$operation failed")
        } catch (_: RuntimeException) {
        }
    }

    /** Class names of [failure] and its causes, outermost first, without any message text. */
    fun describe(failure: Throwable): String =
        generateSequence(failure) { current -> current.cause?.takeUnless { it === current } }
            .take(MAX_CAUSES)
            .joinToString(" <- ") { it.javaClass.simpleName.ifEmpty { it.javaClass.name.substringAfterLast('.') } }
}
