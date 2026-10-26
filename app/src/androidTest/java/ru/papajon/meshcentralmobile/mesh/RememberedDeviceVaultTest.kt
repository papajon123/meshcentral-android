package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.security.RememberedDeviceTrust
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

class RememberedDeviceVaultTest {
    private fun withContext(block: (Context) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("remember-vault-test-$name", mode)
        }
        try { block(isolated) } finally {
            isolated.getSharedPreferences("secure_vault", Context.MODE_PRIVATE).edit().clear().commit()
            isolated.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun recordIsEncryptedAndSurvivesReopeningWithoutCrossingProfiles() = withContext { context ->
        val vault = SecureVault(context, "first")
        assertTrue(vault.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, RememberedDeviceTrust.Platform))
        val stored = context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(stored.contains(COOKIE))
        assertFalse(stored.contains("fixture-user"))
        assertEquals(COOKIE, SecureVault(context, "first").rememberedDevice.get(SERVER, "fixture-user")!!.cookie)
        assertNull(SecureVault(context, "second").rememberedDevice.get(SERVER, "fixture-user"))
    }

    @Test fun clearingCredentialsAlsoClearsRememberedDevice() = withContext { context ->
        val vault = SecureVault(context, "first")
        vault.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, RememberedDeviceTrust.Platform)
        vault.clearCredentials()
        assertNull(vault.rememberedDevice.get(SERVER, "fixture-user"))
    }

    @Test fun editingServerClearsRememberedDeviceEvenBeforeClientReconfiguration() = withContext { context ->
        val settings = SettingsRepository(context)
        settings.updateServerUrl(SERVER)
        val vault = SecureVault(context, settings.activeProfile.id)
        vault.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, RememberedDeviceTrust.Platform)
        settings.updateServerUrl("https://other.example.test/")
        assertNull(vault.rememberedDevice.get(SERVER, "fixture-user"))
    }

    @Test fun switchingProfileClearsTheDepartingProfileOnly() = withContext { context ->
        val settings = SettingsRepository(context)
        settings.updateServerUrl(SERVER)
        val first = SecureVault(context, settings.activeProfile.id)
        val other = settings.createProfile("Other", "https://other.example.test/")
        val second = SecureVault(context, other.id)
        first.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, RememberedDeviceTrust.Platform)
        second.rememberedDevice.save(other.serverUrl, "another-user", "synthetic-other-cookie", 30, RememberedDeviceTrust.Platform)
        assertTrue(settings.activateProfile(other.id))
        assertNull(first.rememberedDevice.get(SERVER, "fixture-user"))
        assertNotNull(second.rememberedDevice.get(other.serverUrl, "another-user"))
    }

    @Test fun deletingInactiveProfileClearsItsRememberedDevice() = withContext { context ->
        val settings = SettingsRepository(context)
        val other = settings.createProfile("Other", SERVER)
        val vault = SecureVault(context, other.id)
        vault.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, RememberedDeviceTrust.Platform)
        assertTrue(settings.removeProfile(other.id))
        assertNull(vault.rememberedDevice.get(SERVER, "fixture-user"))
    }

    @Test fun explicitPinApprovalReplacementAndRemovalEraseRememberedDevice() = withContext { context ->
        val settings = SettingsRepository(context)
        settings.updateServerUrl(SERVER)
        val vault = SecureVault(context, settings.activeProfile.id)
        for (next in listOf("a".repeat(64), "b".repeat(64), null)) {
            val previous = settings.activeProfile.pinnedSpkiSha256
            val trust = previous?.let(RememberedDeviceTrust::Pinned) ?: RememberedDeviceTrust.Platform
            vault.rememberedDevice.save(SERVER, "fixture-user", COOKIE, 30, trust)
            settings.updatePinnedServerKey(next)
            assertNull(vault.rememberedDevice.get(SERVER, "fixture-user"))
        }
    }

    private companion object {
        const val SERVER = "https://server.example.test/team/"
        const val COOKIE = "synthetic-keystore-cookie"
    }
}
