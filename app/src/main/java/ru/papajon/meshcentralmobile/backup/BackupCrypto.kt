package ru.papajon.meshcentralmobile.backup

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

/**
 * Password-based envelope for [BackupCodec] output using only JCA primitives.
 *
 * The envelope is `{format, version, header, ciphertext}`, where `header` is the Base64 encoding of
 * the exact UTF-8 JSON bytes `{format, version, kdf, cipher, iterations, salt, iv}`. Those decoded
 * header bytes are the GCM AAD, so every authenticated parameter is covered byte-for-byte.
 *
 * All header values are validated, and their lengths are bounded, before key derivation. The
 * iteration count is read from the authenticated header but must stay in
 * [MIN_ITERATIONS]..[MAX_ITERATIONS], so a crafted file cannot turn decryption into an unbounded
 * KDF call.
 */
object BackupCrypto {
    private const val FORMAT = "meshcentral-settings-encrypted"
    private const val VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 600_000L
    private const val MAX_ITERATIONS = 5_000_000L
    private const val CIPHER = "AES-256-GCM"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MAX_SALT_BASE64_CHARS = 24
    private const val MAX_IV_BASE64_CHARS = 16
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MIN_PASSWORD_CHARS = 8
    private const val MAX_PASSWORD_CHARS = 1024
    private const val MAX_PLAINTEXT_BYTES = BackupCodec.MAX_BYTES
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES
    private const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024
    private const val MAX_HEADER_BYTES = 4096
    private const val MAX_HEADER_BASE64_CHARS = ((MAX_HEADER_BYTES + 2) / 3) * 4
    private const val MAX_CIPHERTEXT_BASE64_CHARS = ((MAX_CIPHERTEXT_BYTES + 2) / 3) * 4

    private val ENVELOPE_FIELDS = setOf("format", "version", "header", "ciphertext")
    private val HEADER_FIELDS = setOf(
        "format", "version", "kdf", "cipher", "iterations", "salt", "iv",
    )

    /**
     * Shape of the unreleased flat draft: it authenticated a constant AAD and kept every
     * parameter beside a 210_000-iteration ciphertext instead of inside a `header`.
     */
    private val LEGACY_FIELDS = setOf("kdf", "cipher", "iterations", "salt", "iv")
    private val RANDOM = SecureRandom()

    private class SupportedEnvelope(
        val headerBytes: ByteArray,
        val salt: ByteArray,
        val iv: ByteArray,
        val iterations: Int,
        val ciphertext: ByteArray,
    )

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) fail(BackupFailureReason.Limit)

        val salt = ByteArray(SALT_BYTES).also(RANDOM::nextBytes)
        val iv = ByteArray(IV_BYTES).also(RANDOM::nextBytes)
        val header = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", KDF)
            .put("cipher", CIPHER)
            .put("iterations", DEFAULT_ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("iv", Base64.getEncoder().encodeToString(iv))
        val headerBytes = header.toString().toByteArray(StandardCharsets.UTF_8)
        if (headerBytes.size > MAX_HEADER_BYTES) fail(BackupFailureReason.Limit)

        val ciphertext = try {
            withDerivedKey(password, salt, DEFAULT_ITERATIONS) { key ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(headerBytes)
                cipher.doFinal(plaintext)
            }
        } catch (_: GeneralSecurityException) {
            fail(BackupFailureReason.Corrupt)
        }

        val envelope = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("header", Base64.getEncoder().encodeToString(headerBytes))
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))

        val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_ENVELOPE_BYTES) fail(BackupFailureReason.Limit)
        return bytes
    }

    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        requirePassword(password)
        return try {
            decryptChecked(envelope, password)
        } catch (error: BackupException) {
            throw error
        } catch (_: JSONException) {
            fail(BackupFailureReason.Corrupt)
        } catch (_: StackOverflowError) {
            fail(BackupFailureReason.Corrupt)
        } catch (_: IllegalArgumentException) {
            fail(BackupFailureReason.Corrupt)
        }
    }

    /**
     * Password-free envelope classification for callers that decide whether to prompt for a
     * password. Current envelopes are validated through every authenticated parameter and the
     * ciphertext bounds, but PBKDF2 is never invoked. A flat draft envelope without `header` is
     * reported as [BackupFailureReason.UnsupportedFormat]; there is deliberately no legacy
     * decryption path.
     */
    fun requireSupportedEnvelope(bytes: ByteArray) {
        parseSupportedEnvelope(bytes)
    }

    private fun decryptChecked(envelope: ByteArray, password: CharArray): ByteArray {
        val parsed = parseSupportedEnvelope(envelope)
        val plaintext = try {
            withDerivedKey(password, parsed.salt, parsed.iterations) { key ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, parsed.iv))
                cipher.updateAAD(parsed.headerBytes)
                cipher.doFinal(parsed.ciphertext)
            }
        } catch (_: AEADBadTagException) {
            fail(BackupFailureReason.PasswordOrTampered)
        } catch (_: BadPaddingException) {
            fail(BackupFailureReason.PasswordOrTampered)
        } catch (_: GeneralSecurityException) {
            fail(BackupFailureReason.Corrupt)
        }
        if (plaintext.size > MAX_PLAINTEXT_BYTES) fail(BackupFailureReason.Limit)
        return plaintext
    }

    private fun parseSupportedEnvelope(envelope: ByteArray): SupportedEnvelope {
        val root = parseEnvelope(envelope)

        if (requiredString(root, "format") != FORMAT) fail(BackupFailureReason.Corrupt)
        val outerVersion = requiredLong(root, "version")
        if (outerVersion > VERSION.toLong()) fail(BackupFailureReason.NewerVersion)
        if (outerVersion != VERSION.toLong()) fail(BackupFailureReason.Corrupt)

        // A legacy draft has no header at all. Detect it before the field allowlist so the UI can
        // say "unsupported format" instead of asking for a password that can never work.
        if (!root.has("header")) {
            if (LEGACY_FIELDS.all(root::has)) fail(BackupFailureReason.UnsupportedFormat)
            fail(BackupFailureReason.Corrupt)
        }
        requireOnly(root, ENVELOPE_FIELDS)

        val headerText = requiredString(root, "header")
        if (headerText.length > MAX_HEADER_BASE64_CHARS) fail(BackupFailureReason.Limit)
        val headerBytes = decodeBase64(headerText)
        if (headerBytes.isEmpty()) fail(BackupFailureReason.Corrupt)
        if (headerBytes.size > MAX_HEADER_BYTES) fail(BackupFailureReason.Limit)
        val header = parseObject(headerBytes)

        if (requiredString(header, "format") != FORMAT) fail(BackupFailureReason.Corrupt)
        val headerVersion = requiredLong(header, "version")
        if (headerVersion > VERSION.toLong()) fail(BackupFailureReason.NewerVersion)
        if (headerVersion != VERSION.toLong() || headerVersion != outerVersion) {
            fail(BackupFailureReason.Corrupt)
        }
        requireOnly(header, HEADER_FIELDS)

        if (requiredString(header, "kdf") != KDF) fail(BackupFailureReason.Corrupt)
        if (requiredString(header, "cipher") != CIPHER) fail(BackupFailureReason.Corrupt)
        val iterations = requiredLong(header, "iterations")
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) fail(BackupFailureReason.Corrupt)

        val saltText = requiredString(header, "salt")
        if (saltText.length > MAX_SALT_BASE64_CHARS) fail(BackupFailureReason.Corrupt)
        val salt = decodeBase64(saltText)
        if (salt.size != SALT_BYTES) fail(BackupFailureReason.Corrupt)

        val ivText = requiredString(header, "iv")
        if (ivText.length > MAX_IV_BASE64_CHARS) fail(BackupFailureReason.Corrupt)
        val iv = decodeBase64(ivText)
        if (iv.size != IV_BYTES) fail(BackupFailureReason.Corrupt)

        val ciphertextText = requiredString(root, "ciphertext")
        if (ciphertextText.length > MAX_CIPHERTEXT_BASE64_CHARS) fail(BackupFailureReason.Limit)
        val ciphertext = decodeBase64(ciphertextText)
        if (ciphertext.size < TAG_BYTES) fail(BackupFailureReason.Corrupt)
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) fail(BackupFailureReason.Limit)

        return SupportedEnvelope(headerBytes, salt, iv, iterations.toInt(), ciphertext)
    }

    /**
     * Cheap, bounded format probe used by callers that must decide whether to ask for a password.
     * It intentionally checks only the outer format: a newer encrypted envelope must still reach
     * [requireSupportedEnvelope] and be reported as [BackupFailureReason.NewerVersion]. The legacy
     * flat draft also reports true, so the caller can reject it before requesting a password.
     */
    fun isEncrypted(bytes: ByteArray): Boolean = try {
        if (bytes.isEmpty() || bytes.size > MAX_ENVELOPE_BYTES) {
            false
        } else {
            parseObject(bytes).opt("format") == FORMAT
        }
    } catch (_: Exception) {
        false
    } catch (_: StackOverflowError) {
        false
    }

    private fun parseEnvelope(bytes: ByteArray): JSONObject {
        if (bytes.size > MAX_ENVELOPE_BYTES) fail(BackupFailureReason.Limit)
        return parseObject(bytes)
    }

    /**
     * PBEKeySpec and the temporary encoded key are wiped after use. The caller's password array is
     * only read, never modified, so a retry with the same array behaves identically.
     */
    private fun withDerivedKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        block: (SecretKeySpec) -> ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        var secret: SecretKey? = null
        var encoded: ByteArray? = null
        try {
            val generated = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            secret = generated
            val keyBytes = generated.encoded ?: fail(BackupFailureReason.Corrupt)
            encoded = keyBytes
            return block(SecretKeySpec(keyBytes, "AES"))
        } finally {
            spec.clearPassword()
            encoded?.fill(0)
            (secret as? Destroyable)?.let { key -> runCatching { key.destroy() } }
        }
    }

    private fun requirePassword(password: CharArray) {
        if (password.size < MIN_PASSWORD_CHARS || password.size > MAX_PASSWORD_CHARS) {
            throw IllegalArgumentException("Invalid backup password length")
        }
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        fail(BackupFailureReason.Corrupt)
    }

    private fun parseObject(bytes: ByteArray): JSONObject {
        if (bytes.isEmpty()) fail(BackupFailureReason.Corrupt)
        val text = decodeUtf8(bytes)
        return try {
            BackupJsonSyntax.validate(text)
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject) fail(BackupFailureReason.Corrupt)
            if (tokener.nextClean() != '\u0000') fail(BackupFailureReason.Corrupt)
            value
        } catch (error: BackupException) {
            throw error
        } catch (_: JSONException) {
            fail(BackupFailureReason.Corrupt)
        } catch (_: StackOverflowError) {
            fail(BackupFailureReason.Corrupt)
        } catch (_: IllegalArgumentException) {
            fail(BackupFailureReason.Corrupt)
        }
    }

    private fun requireOnly(obj: JSONObject, allowed: Set<String>) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowed) fail(BackupFailureReason.Corrupt)
        }
    }

    private fun requiredString(obj: JSONObject, key: String): String {
        if (!obj.has(key)) fail(BackupFailureReason.Corrupt)
        val value = obj.get(key)
        if (value !is String) fail(BackupFailureReason.Corrupt)
        return value
    }

    private fun requiredLong(obj: JSONObject, key: String): Long {
        if (!obj.has(key)) fail(BackupFailureReason.Corrupt)
        val value = obj.get(key)
        if (value !is Int && value !is Long) fail(BackupFailureReason.Corrupt)
        return (value as Number).toLong()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        fail(BackupFailureReason.Corrupt)
    }

    private fun fail(reason: BackupFailureReason): Nothing = throw BackupException(reason)
}
