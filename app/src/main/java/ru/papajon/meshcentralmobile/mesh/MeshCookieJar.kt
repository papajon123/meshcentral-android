package ru.papajon.meshcentralmobile.mesh

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class MeshCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { stored ->
            stored.expiresAt < now || cookies.any { incoming ->
                incoming.name == stored.name &&
                    incoming.domain == stored.domain &&
                    incoming.path == stored.path
            }
        }
        // twofactor is owned by SecureVault and scoped to one control endpoint/account. Never let
        // Set-Cookie turn it into an ordinary domain cookie (which would also reach relays).
        this.cookies.addAll(cookies.filter { it.expiresAt >= now && it.name != "twofactor" })
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) && it.name != "twofactor" }
    }

    /** The provider captures the account of ONE control request, never mutable current-session state. */
    fun forControlRequest(rememberedCookie: (HttpUrl) -> Cookie?): CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = this@MeshCookieJar.saveFromResponse(url, cookies)
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            this@MeshCookieJar.loadForRequest(url) + listOfNotNull(rememberedCookie(url))
    }

    @Synchronized
    fun hasCookiesFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
