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
        this.cookies.addAll(cookies.filter { it.expiresAt >= now })
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun hasCookiesFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
