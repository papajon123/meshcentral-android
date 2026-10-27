package ru.papajon.meshcentralmobile.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCryptoTest {
    private fun password() = "synthetic-password".toCharArray()

    private fun plaintext() = BASE_PLAINTEXT.copyOf()

    private fun envelope(): JSONObject = JSONObject(BASE_ENVELOPE.toString())

    private fun bytes(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)

    private fun headerBytes(json: JSONObject): ByteArray =
        Base64.getDecoder().decode(json.getString("header"))

    private fun header(json: JSONObject): JSONObject =
        JSONObject(String(headerBytes(json), Charsets.UTF_8))

    private fun withHeader(json: JSONObject, header: JSONObject): JSONObject =
        json.put("header", Base64.getEncoder().encodeToString(header.toString().toByteArray(Charsets.UTF_8)))

    private fun keys(json: JSONObject): Set<String> = buildSet {
        val iterator = json.keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private fun assertFailure(expected: BackupFailureReason, block: () -> Unit) {
        val error = assertThrows(BackupException::class.java) { block() }
        assertEquals(expected, error.reason)
    }

    @Test
    fun roundTripAndEnvelopeProbe() {
        val plain = plaintext()
        val pass = password()
        val encrypted = BackupCrypto.encrypt(plain, pass)
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, pass))
    }

    @Test
    fun envelopeUsesHeaderWithFixedFieldsAndSizes() {
        val json = envelope()
        assertEquals(setOf("format", "version", "header", "ciphertext"), keys(json))
        assertEquals("meshcentral-settings-encrypted", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertFalse(json.has("salt"))
        assertFalse(json.has("iv"))

        val headerJson = header(json)
        assertEquals(
            setOf("format", "version", "kdf", "cipher", "iterations", "salt", "iv"),
            keys(headerJson),
        )
        assertEquals("meshcentral-settings-encrypted", headerJson.getString("format"))
        assertEquals(1, headerJson.getInt("version"))
        assertEquals("PBKDF2-HMAC-SHA256", headerJson.getString("kdf"))
        assertEquals("AES-256-GCM", headerJson.getString("cipher"))
        assertEquals(600_000, headerJson.getInt("iterations"))
        assertEquals(16, Base64.getDecoder().decode(headerJson.getString("salt")).size)
        assertEquals(12, Base64.getDecoder().decode(headerJson.getString("iv")).size)
    }

    @Test
    fun headerBytesAreTheExactAad() {
        val json = envelope()
        val aadBytes = headerBytes(json)
        val headerJson = JSONObject(String(aadBytes, Charsets.UTF_8))
        val salt = Base64.getDecoder().decode(headerJson.getString("salt"))
        val iv = Base64.getDecoder().decode(headerJson.getString("iv"))
        val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
        val spec = PBEKeySpec(password(), salt, headerJson.getInt("iterations"), 256)
        val keyBytes = requireNotNull(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
        )
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aadBytes)
            assertArrayEquals(plaintext(), cipher.doFinal(ciphertext))
        } finally {
            spec.clearPassword()
            keyBytes.fill(0)
        }
    }

    @Test
    fun encryptAndDecryptDoNotModifyCallerPassword() {
        val pass = password()
        val snapshot = pass.copyOf()
        val encrypted = BackupCrypto.encrypt(plaintext(), pass)
        assertArrayEquals(snapshot, pass)
        BackupCrypto.decrypt(encrypted, pass)
        assertArrayEquals(snapshot, pass)
    }

    @Test
    fun wrongPasswordFailsWithPasswordOrTampered() {
        assertFailure(BackupFailureReason.PasswordOrTampered) {
            BackupCrypto.decrypt(bytes(envelope()), "different-synthetic-password".toCharArray())
        }
    }

    @Test
    fun changedCiphertextFailsWithPasswordOrTampered() {
        val json = envelope()
        val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2].toInt() xor 1).toByte()
        json.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        assertFailure(BackupFailureReason.PasswordOrTampered) {
            BackupCrypto.decrypt(bytes(json), password())
        }
    }

    @Test
    fun changedHeaderFormatKdfOrCipherIsCorrupt() {
        listOf(
            "format" to "synthetic-format",
            "kdf" to "PBKDF2-HMAC-SHA1",
            "cipher" to "AES-128-GCM",
        ).forEach { (field, value) ->
            val json = envelope()
            val changed = header(json).put(field, value)
            assertFailure(BackupFailureReason.Corrupt) {
                BackupCrypto.decrypt(bytes(withHeader(json, changed)), password())
            }
        }
    }

    @Test
    fun changedHeaderVersionIsNewerVersion() {
        val json = envelope()
        val changed = header(json).put("version", 2)
        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.decrypt(bytes(withHeader(json, changed)), password())
        }
    }

    @Test
    fun changedIterationsInsideTheAllowedRangeFailsAuthentication() {
        val json = envelope()
        val changed = header(json).put("iterations", 600_001)
        assertFailure(BackupFailureReason.PasswordOrTampered) {
            BackupCrypto.decrypt(bytes(withHeader(json, changed)), password())
        }
    }

    @Test
    fun changedSaltOrIvFailsAuthentication() {
        listOf("salt", "iv").forEach { field ->
            val json = envelope()
            val changed = header(json)
            val value = Base64.getDecoder().decode(changed.getString(field))
            value[0] = (value[0].toInt() xor 1).toByte()
            changed.put(field, Base64.getEncoder().encodeToString(value))
            assertFailure(BackupFailureReason.PasswordOrTampered) {
                BackupCrypto.decrypt(bytes(withHeader(json, changed)), password())
            }
        }
    }

    @Test
    fun changedOuterVersionAndFormatHaveDistinctReasons() {
        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.decrypt(bytes(envelope().put("version", 2)), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("version", 0)), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("format", "meshcentral-settings")), password())
        }
    }

    @Test
    fun iterationsMustBeInsideTheSupportedRange() {
        listOf(0L, 210_000L, 599_999L, 5_000_001L, -1L, "600000").forEach { value ->
            val json = envelope()
            val changed = header(json).put("iterations", value)
            assertFailure(BackupFailureReason.Corrupt) {
                BackupCrypto.decrypt(bytes(withHeader(json, changed)), password())
            }
        }
    }

    @Test
    fun rejectsMalformedTruncatedTrailingAndOversizedEnvelopes() {
        val encrypted = bytes(envelope())
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(encrypted.copyOf(encrypted.size / 2), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(
                (String(encrypted, Charsets.UTF_8) + "{}").toByteArray(Charsets.UTF_8),
                password(),
            )
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt("not json".toByteArray(Charsets.UTF_8), password())
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCrypto.decrypt(ByteArray(5 * 1024 * 1024), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(ByteArray(0), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d), password())
        }
    }

    @Test
    fun rejectsInvalidEnvelopeFieldsAndUnknownFields() {
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("salt", "!!!")), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(
                bytes(envelope().put("iv", Base64.getEncoder().encodeToString(ByteArray(11)))),
                password(),
            )
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("ciphertext", "!!!")), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(
                bytes(envelope().put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(15)))),
                password(),
            )
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("syntheticExtra", "value")), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().also { it.remove("format") }), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().also { it.remove("header") }), password())
        }
    }

    @Test
    fun rejectsUnknownMissingOrInvalidHeaderFields() {
        val unknown = envelope()
        val unknownHeader = header(unknown).put("syntheticExtra", "value")
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(withHeader(unknown, unknownHeader)), password())
        }

        val missingSalt = envelope()
        val missingSaltHeader = header(missingSalt).also { it.remove("salt") }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(withHeader(missingSalt, missingSaltHeader)), password())
        }

        val shortSalt = envelope()
        val shortSaltHeader = header(shortSalt)
            .put("salt", Base64.getEncoder().encodeToString(ByteArray(17)))
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(withHeader(shortSalt, shortSaltHeader)), password())
        }

        val badIv = envelope()
        val badIvHeader = header(badIv)
            .put("iv", Base64.getEncoder().encodeToString(ByteArray(13)))
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(withHeader(badIv, badIvHeader)), password())
        }
    }

    @Test
    fun rejectsUnsupportedVersionAndFormat() {
        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.decrypt(bytes(envelope().put("version", 2)), password())
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(envelope().put("format", "meshcentral-settings")), password())
        }
    }

    @Test
    fun enforcesSizeLimitsBeforeKeyDerivation() {
        assertFailure(BackupFailureReason.Limit) {
            BackupCrypto.encrypt(ByteArray(2 * 1024 * 1024 + 1), password())
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCrypto.decrypt(bytes(envelope().put("header", "A".repeat(6000))), password())
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCrypto.decrypt(
                bytes(
                    envelope().put(
                        "ciphertext",
                        Base64.getEncoder().encodeToString(ByteArray(2 * 1024 * 1024 + 17)),
                    ),
                ),
                password(),
            )
        }
    }

    @Test
    fun enforcesPasswordLengthWithoutChangingInput() {
        val short = "short".toCharArray()
        val shortSnapshot = short.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt(plaintext(), short)
        }
        assertArrayEquals(shortSnapshot, short)

        val long = CharArray(1025) { 'a' }
        val longSnapshot = long.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt(plaintext(), long)
        }
        assertArrayEquals(longSnapshot, long)

        val minimum = "12345678".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext(), minimum)
        assertArrayEquals(plaintext(), BackupCrypto.decrypt(encrypted, minimum))
    }

    @Test
    fun knownLegacyFlatEnvelopeIsRejectedBeforeKeyDerivation() {
        val legacy = LEGACY_ENVELOPE.toByteArray(Charsets.UTF_8)
        assertTrue(BackupCrypto.isEncrypted(legacy))
        assertFailure(BackupFailureReason.UnsupportedFormat) {
            BackupCrypto.requireSupportedEnvelope(legacy)
        }
        assertFailure(BackupFailureReason.UnsupportedFormat) {
            BackupCrypto.decrypt(legacy, password())
        }
    }

    @Test
    fun requireSupportedEnvelopeClassifiesCurrentNewerAndBrokenEnvelopes() {
        BackupCrypto.requireSupportedEnvelope(bytes(envelope()))

        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.requireSupportedEnvelope(bytes(envelope().put("version", 2)))
        }
        val newerHeaderEnvelope = envelope()
        val newerHeader = header(newerHeaderEnvelope).put("version", 2)
        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.requireSupportedEnvelope(bytes(withHeader(newerHeaderEnvelope, newerHeader)))
        }

        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.requireSupportedEnvelope(bytes(envelope().put("header", "!!!")))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.requireSupportedEnvelope(bytes(envelope().also { it.remove("header") }))
        }
    }

    @Test
    fun isEncryptedIsABoundedProbeAndLetsNewerVersionsReachDecrypt() {
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
        assertFalse(BackupCrypto.isEncrypted("[]".toByteArray(Charsets.UTF_8)))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(5 * 1024 * 1024)))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(64) { (it * 17).toByte() }))
        assertFalse(BackupCrypto.isEncrypted(plaintext()))

        val encrypted = bytes(envelope())
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(encrypted.copyOf(encrypted.size / 2)))

        val newer = envelope().put("version", 2)
        assertTrue(BackupCrypto.isEncrypted(bytes(newer)))
        assertFailure(BackupFailureReason.NewerVersion) {
            BackupCrypto.decrypt(bytes(newer), password())
        }

        val brokenHeader = envelope().put("header", "!!!")
        assertTrue(BackupCrypto.isEncrypted(bytes(brokenHeader)))
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCrypto.decrypt(bytes(brokenHeader), password())
        }
    }

    companion object {
        private const val SYNTHETIC_PASSWORD = "synthetic-password"

        // The unreleased flat draft used 210_000 iterations and a constant AAD, so this envelope
        // can never be decrypted by the current header-bound format. No password/KDF is needed.
        private const val LEGACY_ENVELOPE =
            "{\"format\":\"meshcentral-settings-encrypted\",\"version\":1," +
                "\"kdf\":\"PBKDF2-HMAC-SHA256\",\"iterations\":210000," +
                "\"cipher\":\"AES-256-GCM\",\"salt\":\"AAAAAAAAAAAAAAAAAAAAAA==\"," +
                "\"iv\":\"AAAAAAAAAAAAAAAA\",\"ciphertext\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}"
        private val BASE_PLAINTEXT = """
            {"format":"meshcentral-settings","version":1,
             "synthetic":"https://example.invalid/","loginKey":"synthetic-login-key"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        private val BASE_ENVELOPE: JSONObject by lazy {
            JSONObject(
                String(
                    BackupCrypto.encrypt(BASE_PLAINTEXT, SYNTHETIC_PASSWORD.toCharArray()),
                    Charsets.UTF_8,
                ),
            )
        }
    }
}
