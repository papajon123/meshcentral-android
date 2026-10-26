package ru.papajon.meshcentralmobile.security

import ru.papajon.meshcentralmobile.json.optText

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** One active account per profile, exactly like its credentials. The backing record MUST be encrypted. */
class RememberedDeviceStore internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val remove: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Device internal constructor(val cookie: String, val expiresAt: Long, val trust: RememberedDeviceTrust) {
        override fun toString(): String = "RememberedDevice(expiresAt=$expiresAt)"
    }

    @Synchronized
    fun save(serverUrl: String, username: String, cookie: String, days: Int, trust: RememberedDeviceTrust): Boolean {
        val server = CredentialScope.normalize(serverUrl) ?: return false
        if (username.isBlank() || days <= 0 || !validCookie(cookie)) return false
        val created = now()
        val expires = runCatching { Math.addExact(created, days * DAY_MILLIS) }.getOrNull() ?: return false
        write(JSONObject().put("server", server).put("username", username.trim())
            .put("cookie", cookie).put("createdAt", created).put("expiresAt", expires).put("days", days)
            .put("trust", encodeTrust(trust)).toString())
        return true
    }

    @Synchronized
    fun get(serverUrl: String, username: String): Device? {
        val json = runCatching { read()?.let(::JSONObject) }.getOrElse { clear(); return null } ?: return null
        val expires = json.optLong("expiresAt", 0)
        val created = json.optLong("createdAt", Long.MAX_VALUE)
        val cookie = json.optText("cookie")
        val trust = decodeTrust(json.optText("trust")) ?: run { clear(); return null }
        if (now() >= expires || now() < created || !validCookie(cookie)) { clear(); return null }
        if (!CredentialScope.matches(json.optText("server"), serverUrl) ||
            json.optText("username") != username.trim()) return null
        return Device(cookie, expires, trust)
    }

    /** Used only by the control client's CookieJar, never by relays or the WebView. */
    fun cookieForRequest(serverUrl: String, username: String, requestUrl: HttpUrl, allowHttp: Boolean,
        trust: RememberedDeviceTrust?): Cookie? {
        val server = CredentialScope.normalize(serverUrl)?.toHttpUrlOrNull() ?: return null
        if (trust == null) return null
        if (!server.isHttps && !allowHttp) return null
        if (server.isHttps == (trust == RememberedDeviceTrust.DebugHttp)) return null
        if (requestUrl != server.resolve("control.ashx")) return null
        val device = get(serverUrl, username) ?: return null
        if (device.trust != trust) return null
        return Cookie.Builder().name("twofactor").value(device.cookie).hostOnlyDomain(server.host)
            .path(requestUrl.encodedPath).expiresAt(device.expiresAt).httpOnly()
            .apply { if (server.isHttps) secure() }.build()
    }

    @Synchronized
    fun clear() = remove()

    /** Restoring the same saved pin at startup is not a new trust approval. */
    @Synchronized
    fun bindTrust(trust: RememberedDeviceTrust?) {
        val json = runCatching { read()?.let(::JSONObject) }.getOrElse { clear(); return } ?: return
        if (trust == null || decodeTrust(json.optText("trust")) != trust) clear()
    }

    /** Switching endpoint/account must destroy the old record, including after a process restart. */
    @Synchronized
    fun bind(serverUrl: String, username: String? = null) {
        val json = runCatching { read()?.let(::JSONObject) }.getOrElse { clear(); return } ?: return
        if (!CredentialScope.matches(json.optText("server"), serverUrl) ||
            (username != null && json.optText("username") != username.trim())) clear()
    }

    companion object {
        const val DEFAULT_DAYS = 30
        private const val DAY_MILLIS = 86_400_000L

        private fun encodeTrust(trust: RememberedDeviceTrust): String = when (trust) {
            RememberedDeviceTrust.Platform -> "platform"
            is RememberedDeviceTrust.Pinned -> "spki:${trust.spkiSha256}"
            RememberedDeviceTrust.DebugHttp -> "debug-http"
        }

        private fun decodeTrust(value: String): RememberedDeviceTrust? = when {
            value == "platform" -> RememberedDeviceTrust.Platform
            value == "debug-http" -> RememberedDeviceTrust.DebugHttp
            value.matches(Regex("spki:[0-9a-f]{64}")) -> RememberedDeviceTrust.Pinned(value.removePrefix("spki:"))
            else -> null
        }

        /** Zero disables issuance; malformed/nonpositive values have the server's 30-day fallback. */
        fun durationDays(value: Any?): Int {
            val number = (value as? Number)?.toDouble() ?: return DEFAULT_DAYS
            if (number == 0.0) return 0
            if (!number.isFinite() || number < 1) return DEFAULT_DAYS
            // Round down fractional durations: never retain a cookie beyond its server-side lifetime.
            return number.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        }

        fun validCookie(value: String): Boolean = value.length in 1..8192 && value.all {
            it.code in 0x21..0x7e && it !in "\";,\\"
        }
    }
}
