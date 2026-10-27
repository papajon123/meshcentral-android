package ru.papajon.meshcentralmobile.model

import ru.papajon.meshcentralmobile.json.optText

import org.json.JSONArray
import org.json.JSONObject
import ru.papajon.meshcentralmobile.security.CredentialScope

/** Public profile metadata. Authentication material belongs exclusively in SecureVault. */
data class ServerProfile(
    val id: String,
    val name: String,
    val serverUrl: String,
    val onboardingCompleted: Boolean = false,
    val allowHttpInDebug: Boolean = false,
    val trustSelfSignedCertificates: Boolean = false,
    val customShortcuts: List<CustomShortcut> = emptyList(),
    val favoriteCommands: List<FavoriteCommand> = emptyList(),
    val pinnedSpkiSha256: String? = null,
    /** Imported metadata, never a trust grant. */
    val expectedSpkiSha256: String? = null,
) {
    /** A pin belongs to this profile and normalized endpoint (including host, port and domain path). */
    fun withServerUrl(url: String): ServerProfile = copy(
        serverUrl = url,
        pinnedSpkiSha256 = pinnedSpkiSha256.takeIf { CredentialScope.matches(serverUrl, url) },
        expectedSpkiSha256 = expectedSpkiSha256.takeIf { CredentialScope.matches(serverUrl, url) },
    )
}

object ProfileCatalog {
    const val DEFAULT_ID = "default"
    const val MAX_PROFILES = 20

    fun encode(profiles: List<ServerProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("serverUrl", profile.serverUrl)
                .put("onboardingCompleted", profile.onboardingCompleted)
                .put("allowHttpInDebug", profile.allowHttpInDebug)
                .put("pinnedSpkiSha256", profile.pinnedSpkiSha256)
                .put("expectedSpkiSha256", profile.expectedSpkiSha256)
                .put("trustSelfSignedCertificates", profile.trustSelfSignedCertificates)
                .put("customShortcuts", CustomShortcut.encode(profile.customShortcuts))
                .put("favoriteCommands", FavoriteCommand.encode(profile.favoriteCommands)))
        }
    }.toString()

    fun decode(raw: String?): List<ServerProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList<ServerProfile> {
            for (index in 0 until minOf(array.length(), MAX_PROFILES)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optText("id")
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || any { it.id == id }) continue
                add(ServerProfile(id, item.optText("name").take(80).ifBlank { "MeshCentral" },
                    item.optText("serverUrl"), item.optBoolean("onboardingCompleted"),
                    item.optBoolean("allowHttpInDebug"), item.optBoolean("trustSelfSignedCertificates"),
                    customShortcuts = CustomShortcut.decode(item.optJSONArray("customShortcuts")),
                    favoriteCommands = FavoriteCommand.decode(item.optJSONArray("favoriteCommands")),
                    pinnedSpkiSha256 = item.optText("pinnedSpkiSha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) && item.optText("expectedSpkiSha256").isEmpty() },
                    expectedSpkiSha256 = item.optText("expectedSpkiSha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) }))
            }
        }
    }.getOrDefault(emptyList())
}
