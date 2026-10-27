package ru.papajon.meshcentralmobile.discovery

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/** Explicit preparation of an owned emulator for real-time adb swipes outside the Compose test clock.
 * Intentionally leaves the synthetic account encrypted in that debug install; clear app data afterwards.
 */
class LargeServerScrollSetupTest {
    @Test fun prepareOwnedEmulatorForRealTimeScrolling() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeScroll") == "4445")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val fixture = try {
            http.newCall(Request.Builder().url("http://10.0.2.2:18885/fixture").build()).execute().use {
                assertEquals(200, it.code); JSONObject(it.body!!.string())
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        val url = fixture.getString("serverUrl")
        assertEquals("https://10.0.2.2:4445/", url)
        val settings = SettingsRepository(context)
        settings.updateServerUrl(url)
        settings.updateTrustSelfSignedCertificates(true)
        settings.updateBlockScreenshots(false)
        settings.updateBiometricUnlock(false)
        settings.completeOnboarding()
        val vault = SecureVault(context, settings.activeProfile.id)
        vault.clearPin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = MeshCentralClient(vault, scope)
        try {
            client.setAllowSelfSignedCertificates(true)
            client.login(url, fixture.getString("username"), fixture.getString("password"))
            runBlocking { withTimeout(30_000) { client.clientState.first { it.devicesCurrent && it.devicePage.totalCount == 2000 } } }
            settings.addFavorite("Lab 0", "node//" + "0".repeat(64))
            val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            val key = prefs.all.keys.single { it.startsWith("profile_favorites.${settings.activeProfile.id}.") }
            val favorites = org.json.JSONArray()
            repeat(2000) { favorites.put(JSONObject().put("name", "Lab $it").put("url", "node//" + it.toString().padStart(64, '0'))) }
            check(prefs.edit().putString(key, favorites.toString()).commit())
            assertEquals(2000, SettingsRepository(context).favoritesState.value.size)
        } finally { client.close(); scope.cancel() }
    }
}
