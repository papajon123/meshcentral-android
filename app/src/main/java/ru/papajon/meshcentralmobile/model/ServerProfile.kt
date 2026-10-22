package ru.papajon.meshcentralmobile.model

import org.json.JSONArray
import org.json.JSONObject

/** Public profile metadata. Authentication material belongs exclusively in SecureVault. */
data class ServerProfile(
    val id: String,
    val name: String,
    val serverUrl: String,
    val onboardingCompleted: Boolean = false,
    val allowHttpInDebug: Boolean = false,
    val trustSelfSignedCertificates: Boolean = false,
)

object ProfileCatalog {
    const val DEFAULT_ID = "default"
    const val MAX_PROFILES = 20

    fun encode(profiles: List<ServerProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("serverUrl", profile.serverUrl)
                .put("onboardingCompleted", profile.onboardingCompleted)
                .put("allowHttpInDebug", profile.allowHttpInDebug)
                .put("trustSelfSignedCertificates", profile.trustSelfSignedCertificates))
        }
    }.toString()

    fun decode(raw: String?): List<ServerProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList<ServerProfile> {
            for (index in 0 until minOf(array.length(), MAX_PROFILES)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || any { it.id == id }) continue
                add(ServerProfile(id, item.optString("name").take(80).ifBlank { "MeshCentral" },
                    item.optString("serverUrl"), item.optBoolean("onboardingCompleted"),
                    item.optBoolean("allowHttpInDebug"), item.optBoolean("trustSelfSignedCertificates")))
            }
        }
    }.getOrDefault(emptyList())
}
