package ru.papajon.meshcentralmobile.mesh

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** A missing transport is not a server refusal. Only an accepted session resets this budget. */
internal class ConnectionRetryPolicy {
    @Volatile var serverRetries = 0
        private set
    @Volatile var exhausted = false
        private set
    private var networkFailures = 0

    @Synchronized fun nextDelay(networkFailure: Boolean): Long? {
        if (exhausted) return null
        if (networkFailure) {
            networkFailures = (networkFailures + 1).coerceAtMost(6)
            return backoff(networkFailures)
        }
        if (serverRetries >= MAX_SERVER_RETRIES) {
            exhausted = true
            return null
        }
        serverRetries++
        return backoff(serverRetries)
    }

    @Synchronized fun reset() {
        serverRetries = 0
        networkFailures = 0
        exhausted = false
    }

    companion object {
        const val MAX_SERVER_RETRIES = 5
        private fun backoff(attempt: Int) = (1_000L shl (attempt - 1)).coerceAtMost(30_000L)

        fun isNetworkFailure(error: Throwable, responseCode: Int?, opened: Boolean, networkAvailable: Boolean): Boolean {
            // TLS/protocol failures must never become an unlimited network retry, even offline.
            val causes = generateSequence(error) { it.cause }.take(16).toList()
            if (responseCode != null || causes.any { it is SSLException || it is ProtocolException }) return false
            return !networkAvailable || (!opened && causes.any { it is IOException })
        }
    }
}
