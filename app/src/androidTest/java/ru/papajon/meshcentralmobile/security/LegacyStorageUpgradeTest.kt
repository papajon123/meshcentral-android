package ru.papajon.meshcentralmobile.security

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.settings.SettingsRepository

class LegacyStorageUpgradeTest {
    @Test fun legacyPinSettingsAndFavoritesSurviveWhileUnscopedCredentialsRequireSignIn() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("legacy-upgrade-fixture-$name", mode)
        }
        val secure = context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val server = "https://meshcentral.example.com/"
        try {
            secure.edit().clear().commit()
            settings.edit().clear().putString("server_url", server)
                .putBoolean("onboarding_done", true).putBoolean("biometric_unlock", true)
                .putBoolean("block_screenshots", true).putLong("lock_timeout_seconds", 45)
                .putString("favorites", """[{"name":"Fixture desktop","url":"https://meshcentral.example.com/desktop"}]""")
                .commit()
            val salt = ByteArray(16) { it.toByte() }
            val oldHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("6042".toCharArray(), salt, 120_000, 256)).encoded
            val oldPin = JSONObject().put("salt", encode(salt)).put("hash", encode(oldHash)).toString()
            val oldCredentials = encryptOldPayload("""{"username":"legacy-fixture","password":"synthetic-only"}""")
            secure.edit().putString("pin", encryptOldPayload(oldPin)).putString("credentials", oldCredentials).commit()

            val repository = SettingsRepository(context)
            val vault = SecureVault(context)
            assertEquals(server, repository.getSettings().serverUrl)
            assertTrue(repository.getSettings().onboardingCompleted)
            assertTrue(repository.getSettings().biometricUnlockEnabled)
            assertTrue(repository.getSettings().blockScreenshots)
            assertEquals(45L, repository.getSettings().lockTimeoutSeconds)
            assertEquals("Fixture desktop", repository.favoritesState.value.single().name)
            assertTrue(vault.hasPin())
            assertNull(vault.pinLength())
            assertFalse(vault.verifyPin("604203"))
            assertNull(vault.pinLength())
            assertTrue(vault.verifyPin("6042"))
            assertEquals(4, SecureVault(context).pinLength())
            assertFalse(vault.verifyPin("0000"))
            assertNull(vault.getCredentials(server))
            assertEquals(oldCredentials, secure.getString("credentials", null))

            vault.saveCredentials("fresh-fixture", "synthetic-only", server)
            assertEquals("fresh-fixture", vault.getCredentials(server)?.username)
            assertNull(vault.getCredentials("https://other.example.com/"))
            assertTrue(vault.verifyPin("6042"))
            vault.savePin("604203")
            assertEquals(6, SecureVault(context).pinLength())
            assertFalse(vault.verifyPin("6042"))
            assertTrue(vault.verifyPin("604203"))
        } finally {
            secure.edit().clear().commit()
            settings.edit().clear().commit()
        }
    }

    private fun encryptOldPayload(plaintext: String): String {
        val alias = "meshcentral_mobile_secure_vault"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject().put("iv", encode(cipher.iv)).put("ciphertext", encode(data)).toString()
    }

    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
}
