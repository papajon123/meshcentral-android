package ru.papajon.meshcentralmobile.notifications

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONObject

/**
 * Validates the one-time registration response before anything is written to SecureVault.
 * The token opens with the `notifications` scope only when its endpoint stays on the configured
 * origin and the TTL is plausible for the 30-day server contract.
 */
object NotificationRegistrationParser {
    fun parse(
        data: JSONObject,
        serverUrl: String,
        userId: String,
        now: Long,
    ): NotificationToken {
        val token = data.optText("token")
        val tokenId = data.optText("tokenId")
        val endpoint = data.optText("endpoint")
        val expiresAt = data.optLong("expiresAt", -1L)
        if (token.length != 43 || !token.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            throw NotificationProtocolException("invalid token")
        }
        if (!tokenId.matches(Regex("mcn_[0-9a-f]{16}"))) {
            throw NotificationProtocolException("invalid token id")
        }
        if (!NotificationEndpoint.isValidPath(endpoint) ||
            NotificationEndpoint.resolve(serverUrl, endpoint) == null
        ) {
            throw NotificationProtocolException("invalid endpoint")
        }
        if (expiresAt <= now || expiresAt > now + NotificationContract.MAX_TOKEN_TTL_MILLIS) {
            throw NotificationProtocolException("invalid token ttl")
        }
        if (!userId.matches(Regex("[A-Za-z0-9_.:/@-]{1,256}"))) {
            throw NotificationProtocolException("invalid user")
        }
        return NotificationToken(tokenId, token, endpoint, expiresAt, userId)
    }
}
