package ru.papajon.meshcentralmobile.profiles

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import java.util.UUID

class ProfileStorageTest {
    @Test fun accessKeysAreEncryptedAndBoundToProfileAndCompleteEndpoint() {
        val first = SecureVault(context, "first", vaultName)
        val second = SecureVault(context, "second", vaultName)
        val server = "https://mesh.example/team/"
        val secret = "synthetic-access-key"
        first.saveLoginKey(server, secret)
        assertEquals(secret, SecureVault(context, "first", vaultName).getLoginKey(server))
        assertNull(second.getLoginKey(server))
        assertNull(first.getLoginKey("https://mesh.example/other/"))
        assertNull(first.getLoginKey("https://mesh.example:4443/team/"))
        assertNull(first.getLoginKey("http://mesh.example/team/"))
        assertFalse(context.getSharedPreferences(vaultName, Context.MODE_PRIVATE).all.values.any {
            it.toString().contains(secret)
        })
        first.clearCredentials()
        assertEquals(secret, first.getLoginKey(server)) // A server setting survives account logout.
        second.saveLoginKey(server, "synthetic-second-key")
        first.clearLoginKey() // Profile deletion must clear only that profile.
        assertNull(first.getLoginKey(server))
        assertEquals("synthetic-second-key", second.getLoginKey(server))
        second.saveLoginKey(server, "")
        assertNull(second.getLoginKey(server))
    }
    @Test fun favoriteCommandsPersistPerProfileWithTwentyItemLimit() {
        val store = SettingsRepository(context, name)
        val first = store.activeProfile.id
        val other = store.createProfile("Other", "https://other.example/")
        repeat(20) { assertTrue(store.addFavoriteCommand(first, ru.papajon.meshcentralmobile.model.FavoriteCommand("Example $it", "echo $it"))) }
        assertFalse(store.addFavoriteCommand(first, ru.papajon.meshcentralmobile.model.FavoriteCommand("Extra", "echo extra")))
        assertTrue(store.activateProfile(other.id))
        assertTrue(store.activeProfile.favoriteCommands.isEmpty())
        val reread = SettingsRepository(context, name)
        assertTrue(reread.activeProfile.favoriteCommands.isEmpty())
        reread.activateProfile(first)
        assertEquals(20, reread.activeProfile.favoriteCommands.size)
        reread.removeFavoriteCommand(first, 0)
        assertEquals("echo 1", SettingsRepository(context, name).activeProfile.favoriteCommands.first().command)
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "profile-test-${UUID.randomUUID()}"
    private val vaultName = "$name-vault"

    @After fun clearFixtures() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(vaultName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun migrationAndSwitchingPreserveLegacySettingsAndSeparateFavorites() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("server_url", "https://home.example/").putBoolean("onboarding_done", true)
            .putBoolean("block_screenshots", true).putLong("lock_timeout_seconds", 300)
            .putString("favorites", """[{"name":"Home PC","url":"node//same"}]""").commit()
        val store = SettingsRepository(context, name)
        assertEquals(ProfileCatalog.DEFAULT_ID, store.activeProfile.id)
        assertEquals("node//same", store.favoritesState.value.single().url)
        val work = store.createProfile("Work", "https://work.example/")
        assertTrue(store.activateProfile(work.id))
        assertTrue(store.favoritesState.value.isEmpty())
        assertTrue(store.getSettings().blockScreenshots)
        assertEquals(300L, store.getSettings().lockTimeoutSeconds)
        store.addFavorite("Work PC", "node//same")
        store.completeOnboarding()
        assertTrue(store.activateProfile("default"))
        assertEquals("Home PC", store.favoritesState.value.single().name)
        val reloaded = SettingsRepository(context, name)
        assertEquals(2, reloaded.profilesState.value.size)
        assertEquals("https://home.example/", reloaded.getSettings().serverUrl)
        reloaded.activateProfile(work.id)
        assertEquals("Work PC", reloaded.favoritesState.value.single().name)
        assertTrue(reloaded.getSettings().onboardingCompleted)
        assertTrue(reloaded.removeProfile(work.id))
        assertFalse(reloaded.removeProfile("default"))
    }

    @Test fun changingAnAddressDoesNotExposeAnotherServersFavorites() {
        val store = SettingsRepository(context, name)
        store.updateServerUrl("https://first.example/")
        store.addFavorite("First", "node//same")
        store.updateServerUrl("https://second.example/")
        assertTrue(store.favoritesState.value.isEmpty())
        store.updateServerUrl("https://first.example/")
        assertEquals("First", store.favoritesState.value.single().name)
    }

    @Test fun catalogRecoveryDoesNotResurrectAnExplicitlyDeletedProfile() {
        val store = SettingsRepository(context, name)
        val removed = store.createProfile("Temporary", "https://temporary.example/")
        assertTrue(store.removeProfile(removed.id))
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("server_profiles_v1", "corrupt-json").commit()
        val recovered = SettingsRepository(context, name)
        assertEquals(listOf(ProfileCatalog.DEFAULT_ID), recovered.profilesState.value.map { it.id })
    }

    @Test fun credentialsMigrateOnceAndProfilesNeverShareASecretOrClearThePin() {
        val legacy = SecureVault(context, preferencesName = vaultName)
        legacy.saveCredentials("legacy-operator", "synthetic-fixture", "https://home.example/")
        legacy.savePin("6042")
        val home = SecureVault(context, "default", vaultName)
        home.migrateLegacyCredentials("https://home.example/")
        assertEquals("legacy-operator", home.getCredentials("https://home.example/")?.username)
        val other = SecureVault(context, "other", vaultName)
        assertNull(other.getCredentials("https://home.example/"))
        other.saveCredentials("other-operator", "separate-fixture", "https://home.example/")
        assertEquals("legacy-operator", home.getCredentials("https://home.example/")?.username)
        home.clearCredentials()
        home.migrateLegacyCredentials("https://home.example/")
        assertNull(home.getCredentials("https://home.example/"))
        assertNull(legacy.getCredentials("https://home.example/"))
        assertEquals("other-operator", other.getCredentials("https://home.example/")?.username)
        assertTrue(home.verifyPin("6042"))
    }
}
