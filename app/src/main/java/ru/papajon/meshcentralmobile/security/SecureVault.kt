package ru.papajon.meshcentralmobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import ru.papajon.meshcentralmobile.model.StoredCredentials
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class SecureVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun saveCredentials(username: String, password: String, serverUrl: String) {
        val server = requireNotNull(CredentialScope.normalize(serverUrl)) { "Invalid credential server" }
        val payload = JSONObject()
            .put("server", server)
            .put("username", username)
            .put("password", password)
            .toString()
        putEncrypted(KEY_CREDENTIALS, payload)
    }

    fun getCredentials(serverUrl: String): StoredCredentials? {
        val payload = getEncrypted(KEY_CREDENTIALS) ?: return null
        val json = JSONObject(payload)
        // Older, unscoped payloads require an explicit sign-in. Never guess their server.
        if (!CredentialScope.matches(json.optString("server").takeIf { it.isNotBlank() }, serverUrl)) return null
        return StoredCredentials(
            username = json.optString("username"),
            password = json.optString("password"),
        )
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_CREDENTIALS).apply()
    }

    fun savePin(pin: String) {
        val salt = ByteArray(PIN_SALT_BYTES)
        random.nextBytes(salt)
        val hash = hashPin(pin, salt)
        val payload = JSONObject()
            .put("salt", salt.encodeBase64())
            .put("hash", hash.encodeBase64())
            .toString()
        putEncrypted(KEY_PIN, payload)
    }

    fun hasPin(): Boolean = prefs.contains(KEY_PIN)

    fun verifyPin(pin: String): Boolean {
        val payload = getEncrypted(KEY_PIN) ?: return false
        val json = JSONObject(payload)
        val salt = json.getString("salt").decodeBase64()
        val expectedHash = json.getString("hash").decodeBase64()
        val actualHash = hashPin(pin, salt)
        return expectedHash.contentEquals(actualHash)
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun putEncrypted(key: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val json = JSONObject()
            .put("iv", cipher.iv.encodeBase64())
            .put("ciphertext", ciphertext.encodeBase64())
            .toString()
        prefs.edit().putString(key, json).apply()
    }

    private fun getEncrypted(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val json = JSONObject(stored)
        val iv = json.getString("iv").decodeBase64()
        val ciphertext = json.getString("ciphertext").decodeBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_HASH_BITS)
        return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val PREFS_NAME = "secure_vault"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "meshcentral_mobile_secure_vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_PIN = "pin"
        private const val PIN_SALT_BYTES = 16
        private const val PIN_ITERATIONS = 120_000
        private const val PIN_HASH_BITS = 256
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}
