package ru.papajon.meshcentralmobile.mesh

import java.util.Base64
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText

/**
 * `x-meshauth` header construction and control-channel failure classification.
 *
 * Verified against MeshCentral 1.3.1 `webserver.js:9442-9539`:
 * the header is `base64(user),base64(password)[,base64(2fa)]`, split on `,` and base64 decoded.
 * A login token uses its generated `~t:` username and token password as two parts, without 2FA.
 * Account credentials may receive a two-factor challenge; three parts answer it. The special third values
 * `**email**`, `**sms**` and `**msg**` ask the server to send a fresh code before answering.
 */
object MeshAuth {
    fun isVerificationCode(token: String?): Boolean = !token.isNullOrBlank() &&
        token !in setOf(REQUEST_EMAIL_TOKEN, REQUEST_SMS_TOKEN, REQUEST_PUSH_TOKEN)

    fun headerValue(username: String, password: String, twoFactorToken: String? = null): String {
        val parts = ArrayList<String>(3)
        parts.add(base64(username))
        parts.add(base64(password))
        if (!twoFactorToken.isNullOrEmpty()) parts.add(base64(twoFactorToken))
        return parts.joinToString(",")
    }

    fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    /** Ask MeshCentral to email a fresh one-time code. */
    const val REQUEST_EMAIL_TOKEN = "**email**"

    /** Ask MeshCentral to send a fresh one-time code by SMS. */
    const val REQUEST_SMS_TOKEN = "**sms**"

    /** Ask MeshCentral to send a fresh one-time code through a push provider. */
    const val REQUEST_PUSH_TOKEN = "**msg**"
}

enum class MeshAuthFailure {
    BadCredentials,
    MalformedHeader,
    TwoFactorRequired,
    AccessKeyRequired,
    Banned,
    ToolsDisabled,
    EmailValidationRequired,
    Unknown,
}

/**
 * Maps `{action:'close', cause, msg}` frames to a reason the UI can show.
 *
 * Message suffixes come from `webserver.js`: `noauth-2a` (bad user/password on the websocket),
 * `noauth-2b` (bad cookie), `noauth-2c` (bad `x-meshauth` header shape).
 */
object MeshAuthDiagnostics {
    fun classify(cause: String?, message: String?): MeshAuthFailure {
        val causeText = cause.orEmpty().lowercase()
        val messageText = message.orEmpty().lowercase()
        return when {
            messageText == "tokenrequired" -> MeshAuthFailure.TwoFactorRequired
            causeText == "banned" -> MeshAuthFailure.Banned
            causeText == "notools" -> MeshAuthFailure.ToolsDisabled
            causeText == "emailvalidation" -> MeshAuthFailure.EmailValidationRequired
            messageText == "nokey" -> MeshAuthFailure.AccessKeyRequired
            messageText == "noauth-2c" -> MeshAuthFailure.MalformedHeader
            causeText == "noauth" -> MeshAuthFailure.BadCredentials
            else -> MeshAuthFailure.Unknown
        }
    }

    fun describe(failure: MeshAuthFailure, challenge: TwoFactorChallenge? = null): UiText {
        return UiText.Resource(
            when (failure) {
                MeshAuthFailure.BadCredentials -> R.string.runtime_auth_bad_credentials
                MeshAuthFailure.MalformedHeader -> R.string.runtime_auth_malformed_header
                MeshAuthFailure.TwoFactorRequired -> R.string.runtime_auth_two_factor_required
                MeshAuthFailure.AccessKeyRequired -> R.string.token_login_key_required
                MeshAuthFailure.Banned -> R.string.runtime_auth_banned
                MeshAuthFailure.ToolsDisabled -> R.string.runtime_auth_tools_disabled
                MeshAuthFailure.EmailValidationRequired -> R.string.runtime_auth_email_validation
                MeshAuthFailure.Unknown -> R.string.runtime_auth_unknown
            },
        )
    }
}
