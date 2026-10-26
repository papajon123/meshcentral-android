package ru.papajon.meshcentralmobile.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.papajon.meshcentralmobile.security.CredentialScope

/** Separates a pasted loginKey from public endpoint metadata. Other URL validation stays intact. */
internal object ServerAddressInput {
    class Parsed(val address: String, val loginKey: String?)

    fun split(input: String): Parsed {
        val url = input.trim().toHttpUrlOrNull() ?: return Parsed(input, null)
        val keys = url.queryParameterValues("key")
        if (keys.size != 1 || keys.single().isNullOrEmpty() || keys.single()!!.length > 8192) return Parsed(input, null)
        return Parsed(url.newBuilder().removeAllQueryParameters("key").build().toString(), keys.single())
    }

    /** Invalid input may contain userinfo, query secrets or fragments; never put it in a Bundle. */
    fun restorableDraft(input: String): String? = input.takeIf { CredentialScope.normalize(it) != null }
}
