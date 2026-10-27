package ru.papajon.meshcentralmobile.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.model.CustomShortcut
import ru.papajon.meshcentralmobile.model.FavoriteCommand
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.model.ServerProfile

class BackupCodecTest {
    private fun assertFailure(expected: BackupFailureReason, block: () -> Unit) {
        val error = assertThrows(BackupException::class.java) { block() }
        assertEquals(expected, error.reason)
    }

    @Test
    fun rejectsLenientJsonBeforeThePlatformParser() {
        val valid = BackupCodec.encode(baseBackup()).decodeToString()
        listOf(
            valid.replace("\"format\":", "format:"),
            valid.replace("\"format\":", "'format':"),
            "{\"format\":/*\"*/" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}",
        ).forEach { raw ->
            assertFailure(BackupFailureReason.Corrupt) { BackupCodec.decode(raw.toByteArray()) }
        }
    }

    private fun profile(
        id: String = "profile_1",
        name: String = "Synthetic profile",
        serverUrl: String = "https://example.invalid/mesh/",
        onboardingCompleted: Boolean = true,
        shortcuts: List<CustomShortcut> = listOf(CustomShortcut("Synthetic shortcut", "F4", alt = true)),
        commands: List<FavoriteCommand> = listOf(FavoriteCommand("Synthetic command", "echo synthetic")),
        pin: String? = "a".repeat(64),
    ) = ServerProfile(
        id = id,
        name = name,
        serverUrl = serverUrl,
        onboardingCompleted = onboardingCompleted,
        customShortcuts = shortcuts,
        favoriteCommands = commands,
        pinnedSpkiSha256 = pin,
    )

    private fun fullBackup() = SettingsBackup(
        profiles = listOf(
            BackupProfile(
                profile = profile(),
                favorites = listOf(FavoriteLink("Synthetic device", "node//synthetic-device")),
                loginKey = "synthetic-login-key",
            ),
            BackupProfile(
                profile = profile(id = "profile_2", name = "Second synthetic", pin = null),
                favorites = listOf(FavoriteLink("Second synthetic device", "node//synthetic-device-2")),
            ),
        ),
        preferences = BackupPreferences(
            blockScreenshots = true,
            lockTimeoutSeconds = 300,
            stylusPrecisionEnabled = false,
            stylusHintSeen = true,
            themeMode = "Dark",
            dynamicColor = true,
            hideDevicesWhenLocked = true,
        ),
    )

    private fun baseBackup() = fullBackup().copy(profiles = fullBackup().profiles.take(1))

    private fun validJson(): JSONObject =
        JSONObject(String(BackupCodec.encode(baseBackup()), Charsets.UTF_8))

    private fun decode(json: JSONObject): SettingsBackup =
        BackupCodec.decode(json.toString().toByteArray(Charsets.UTF_8))

    private fun profileJson(json: JSONObject, index: Int = 0): JSONObject =
        json.getJSONArray("profiles").getJSONObject(index)

    @Test
    fun roundTripPreservesAllowlistedFields() {
        val backup = fullBackup()
        val encoded = BackupCodec.encode(backup, includeLoginKeys = true)
        val decoded = BackupCodec.decode(encoded, allowLoginKeys = true)
        assertEquals(backup, decoded)
    }

    @Test
    fun defaultExportOmitsLoginKeyAndDecodeRejectsIt() {
        val withoutKeys = BackupCodec.encode(fullBackup())
        val text = String(withoutKeys, Charsets.UTF_8)
        assertFalse(text.contains("\"loginKey\""))
        listOf(
            "allowHttpInDebug", "trustSelfSignedCertificates", "biometricUnlockEnabled",
            "password", "username", "cookies", "token",
        ).forEach { field -> assertFalse("field=$field", text.contains("\"$field\"")) }
        val decoded = BackupCodec.decode(withoutKeys)
        assertNull(decoded.profiles.first().loginKey)

        val withKeys = BackupCodec.encode(fullBackup(), includeLoginKeys = true)
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode(withKeys)
        }
    }

    @Test
    fun emitsVersionedFormatWithEmptyExtensions() {
        val json = validJson()
        assertEquals("meshcentral-settings", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(0, json.getJSONObject("extensions").length())

        val withSkippedInput = baseBackup().copy(skippedExtensions = setOf("portForwards"))
        val encoded = JSONObject(String(BackupCodec.encode(withSkippedInput), Charsets.UTF_8))
        assertEquals(0, encoded.getJSONObject("extensions").length())
    }

    @Test
    fun encryptionHidesSyntheticAddressAndLoginKey() {
        val plaintext = BackupCodec.encode(fullBackup(), includeLoginKeys = true)
        val password = "synthetic-password".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        val visible = String(encrypted, Charsets.UTF_8)
        assertFalse(visible.contains("example.invalid"))
        assertFalse(visible.contains("synthetic-login-key"))
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(plaintext))
        assertEquals(
            String(plaintext, Charsets.UTF_8),
            String(BackupCrypto.decrypt(encrypted, password), Charsets.UTF_8),
        )
    }

    @Test
    fun rejectsRandomTruncatedTrailingAndOversizedJson() {
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode(ByteArray(64) { (it * 31).toByte() })
        }

        val valid = BackupCodec.encode(baseBackup())
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode(valid.copyOf(valid.size / 2))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode((String(valid, Charsets.UTF_8) + "null").toByteArray(Charsets.UTF_8))
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1))
        }
    }

    @Test
    fun rejectsInvalidUtf8AndExcessiveDepth() {
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d))
        }
        val deeplyNested = "{\"format\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        assertFailure(BackupFailureReason.Corrupt) {
            BackupCodec.decode(deeplyNested.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertFailure(BackupFailureReason.NewerVersion) {
            decode(validJson().put("version", 2))
        }
        assertFailure(BackupFailureReason.NewerVersion) {
            decode(validJson().put("version", 2L))
        }
        assertFailure(BackupFailureReason.NewerVersion) {
            decode(validJson().put("version", 2).put("futureRootField", "synthetic"))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("version", 0))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("version", "2"))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("version", 1.5))
        }
    }

    @Test
    fun rejectsInvalidProfileFields() {
        val badUrls = listOf(
            "http://example.invalid/",
            "https://user@example.invalid/",
            "https://example.invalid/?query=1",
            "https://example.invalid/#fragment",
            "https://example.invalid/mesh path/",
            "",
        )
        badUrls.forEach { url ->
            assertFailure(BackupFailureReason.Corrupt) {
                decode(validJson().also { profileJson(it).put("serverUrl", url) })
            }
        }

        listOf("A".repeat(64), "a".repeat(63), "g".repeat(64)).forEach { pin ->
            assertFailure(BackupFailureReason.Corrupt) {
                decode(validJson().also { profileJson(it).put("pinnedSpkiSha256", pin) })
            }
        }

        listOf("", "bad id", "../escape", "a".repeat(65)).forEach { id ->
            assertFailure(BackupFailureReason.Corrupt) {
                decode(validJson().also { profileJson(it).put("id", id) })
            }
        }

        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().also { profileJson(it).put("name", "   ") })
        }
    }

    @Test
    fun rejectsDuplicateProfileIds() {
        val json = validJson()
        val profiles = json.getJSONArray("profiles")
        profiles.put(JSONObject(profiles.getJSONObject(0).toString()))
        assertFailure(BackupFailureReason.Corrupt) { decode(json) }
    }

    @Test
    fun rejectsControlCharactersAndUnknownTypes() {
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().also { profileJson(it).put("name", "Synthetic\u0000name") })
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().also { profileJson(it).put("onboardingCompleted", "true") })
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().also { profileJson(it).put("customShortcuts", JSONObject()) })
        }
    }

    @Test
    fun rejectsSecretDebugAndUnknownFields() {
        val forbidden = listOf(
            "password", "username", "cookies", "token", "biometricUnlockEnabled",
            "allowHttpInDebug", "trustSelfSignedCertificates", "pin",
        )
        forbidden.forEach { field ->
            assertFailure(BackupFailureReason.Corrupt) {
                decode(validJson().also { profileJson(it).put(field, "synthetic") })
            }
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("secrets", JSONObject()))
        }
    }

    @Test
    fun decodesUnknownExtensionsAsSkippedAndDoesNotExportThem() {
        val extensions = JSONObject()
            .put("portForwards", JSONArray().put(JSONObject().put("synthetic", true)))
            .put("future", JSONObject().put("nested", JSONArray().put(1)))
            .put("scalar", "synthetic")
        val decoded = decode(validJson().put("extensions", extensions))
        assertEquals(setOf("portForwards", "future", "scalar"), decoded.skippedExtensions)
        assertEquals(1, decoded.profiles.size)

        val reencoded = JSONObject(String(BackupCodec.encode(decoded), Charsets.UTF_8))
        assertEquals(0, reencoded.getJSONObject("extensions").length())

        val empty = decode(validJson().put("extensions", JSONObject()))
        assertTrue(empty.skippedExtensions.isEmpty())
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("extensions", JSONArray()))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("extensions", JSONObject.NULL))
        }
    }

    @Test
    fun boundsSkippedExtensionNames() {
        val tooMany = JSONObject()
        repeat(33) { tooMany.put("extension_$it", JSONObject()) }
        assertFailure(BackupFailureReason.Limit) {
            decode(validJson().put("extensions", tooMany))
        }
        assertFailure(BackupFailureReason.Limit) {
            decode(validJson().put("extensions", JSONObject().put("x".repeat(81), true)))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("extensions", JSONObject().put("synthetic\u0000name", true)))
        }
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().put("extensions", JSONObject().put("   ", true)))
        }
        assertEquals(
            setOf("x".repeat(80)),
            decode(validJson().put("extensions", JSONObject().put("x".repeat(80), true))).skippedExtensions,
        )
    }

    @Test
    fun hideDevicesWhenLockedIsOptionalOnDecodeAndValidated() {
        val withoutKey = validJson().also { it.getJSONObject("preferences").remove("hideDevicesWhenLocked") }
        assertFalse(decode(withoutKey).preferences.hideDevicesWhenLocked)
        assertTrue(decode(validJson()).preferences.hideDevicesWhenLocked)
        assertTrue(
            String(BackupCodec.encode(baseBackup()), Charsets.UTF_8)
                .contains("\"hideDevicesWhenLocked\":true"),
        )
        assertFailure(BackupFailureReason.Corrupt) {
            decode(validJson().also { it.getJSONObject("preferences").put("hideDevicesWhenLocked", "true") })
        }
    }

    @Test
    fun requiresActualMeshCentralFavoriteTargets() {
        val encoded = BackupCodec.encode(
            baseBackup().copy(
                profiles = listOf(
                    BackupProfile(profile(), listOf(FavoriteLink("Synthetic device", "node//synthetic-device"))),
                    BackupProfile(profile(id = "profile_2"), listOf(FavoriteLink("Synthetic mesh", "node/domain/synthetic-device"))),
                ),
            ),
        )
        val decoded = BackupCodec.decode(encoded)
        assertEquals("node//synthetic-device", decoded.profiles[0].favorites.single().url)
        assertEquals("node/domain/synthetic-device", decoded.profiles[1].favorites.single().url)

        listOf("https://example.invalid/", "node/", "mesh://node/example", "node//bad target", "node//bad?query=1").forEach { url ->
            assertFailure(BackupFailureReason.Corrupt) {
                BackupCodec.encode(
                    baseBackup().copy(
                        profiles = listOf(BackupProfile(profile(), listOf(FavoriteLink("Synthetic", url)))),
                    ),
                )
            }
        }
    }

    @Test
    fun enforcesProfileCommandShortcutAndFavoriteLimits() {
        assertFailure(BackupFailureReason.Limit) {
            BackupCodec.encode(SettingsBackup(List(21) { BackupProfile(profile(id = "profile_$it")) }))
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCodec.encode(
                baseBackup().copy(
                    profiles = listOf(
                        BackupProfile(
                            profile(),
                            favorites = List(501) { FavoriteLink("Synthetic $it", "node//synthetic-$it") },
                        ),
                    ),
                ),
            )
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCodec.encode(
                baseBackup().copy(
                    profiles = listOf(
                        BackupProfile(
                            profile().copy(
                                favoriteCommands = List(21) { FavoriteCommand("Synthetic", "echo synthetic") },
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFailure(BackupFailureReason.Limit) {
            BackupCodec.encode(
                baseBackup().copy(
                    profiles = listOf(
                        BackupProfile(
                            profile().copy(
                                customShortcuts = List(13) { CustomShortcut("Synthetic", "F4") },
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun decodeEnforcesCollectionLimits() {
        val tooManyProfiles = validJson()
        val profiles = tooManyProfiles.getJSONArray("profiles")
        val original = JSONObject(profiles.getJSONObject(0).toString())
        repeat(20) { profiles.put(JSONObject(original.toString())) }
        assertFailure(BackupFailureReason.Limit) { decode(tooManyProfiles) }

        assertFailure(BackupFailureReason.Limit) {
            decode(
                validJson().also {
                    profileJson(it).put(
                        "favorites",
                        JSONArray().apply {
                            repeat(501) { index ->
                                put(JSONObject().put("name", "Synthetic").put("url", "node//synthetic-$index"))
                            }
                        },
                    )
                },
            )
        }
        assertFailure(BackupFailureReason.Limit) {
            decode(
                validJson().also {
                    profileJson(it).put(
                        "favoriteCommands",
                        JSONArray().apply {
                            repeat(21) {
                                put(JSONObject().put("name", "Synthetic").put("command", "echo synthetic"))
                            }
                        },
                    )
                },
            )
        }
        assertFailure(BackupFailureReason.Limit) {
            decode(
                validJson().also {
                    profileJson(it).put(
                        "customShortcuts",
                        JSONArray().apply {
                            repeat(13) {
                                put(
                                    JSONObject().put("label", "Synthetic").put("key", "F4")
                                        .put("ctrl", false).put("alt", false).put("shift", false).put("win", false),
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    @Test
    fun encodeRejectsBackupOverTheSizeLimitWithLimitReason() {
        val oversized = SettingsBackup(
            profiles = List(20) { index ->
                BackupProfile(
                    profile = profile(id = "profile_$index"),
                    favorites = List(500) { favorite ->
                        FavoriteLink("Synthetic $favorite", "node//" + "x".repeat(1000))
                    },
                )
            },
        )
        assertFailure(BackupFailureReason.Limit) { BackupCodec.encode(oversized) }
    }
}
