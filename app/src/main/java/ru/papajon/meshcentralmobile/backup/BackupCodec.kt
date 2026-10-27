package ru.papajon.meshcentralmobile.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import ru.papajon.meshcentralmobile.model.CustomShortcut
import ru.papajon.meshcentralmobile.model.FavoriteCommand
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.settings.ServerUrlValidator
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A profile together with the data that belongs to it in the current settings model. */
data class BackupProfile(
    val profile: ServerProfile,
    val favorites: List<FavoriteLink> = emptyList(),
    val loginKey: String? = null,
)

/** User-visible preferences that are safe to move between installations. */
data class BackupPreferences(
    val blockScreenshots: Boolean = false,
    val lockTimeoutSeconds: Long = 60,
    val stylusPrecisionEnabled: Boolean = true,
    val stylusHintSeen: Boolean = false,
    val themeMode: String = "System",
    val dynamicColor: Boolean = false,
    val hideDevicesWhenLocked: Boolean = false,
)

/** The complete version 1 settings backup. */
data class SettingsBackup(
    val profiles: List<BackupProfile>,
    val preferences: BackupPreferences = BackupPreferences(),
    val skippedExtensions: Set<String> = emptySet(),
)

/**
 * Versioned, explicit JSON codec for local settings backups.
 *
 * Only the fields listed below are read or written. Debug HTTP/TLS switches, biometrics, PINs,
 * passwords, cookies, tokens and usernames are intentionally absent: an unknown root or profile
 * field is an error, not something to pass through. Login keys require an explicit opt-in on both
 * encode and decode.
 *
 * `extensions` is the one forward-compatible exception: it must be an object, but its sections are
 * not imported yet. Their names are reported through [SettingsBackup.skippedExtensions] instead,
 * while encode always writes an empty object and never promises to preserve future data.
 */
object BackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024

    private const val FORMAT = "meshcentral-settings"
    private const val VERSION = 1

    private const val MAX_PROFILES = ProfileCatalog.MAX_PROFILES
    private const val MAX_FAVORITES = 500
    private const val MAX_PROFILE_NAME = 80
    private const val MAX_URL = 2048
    private const val MAX_FAVORITE_NAME = 200
    private const val MAX_FAVORITE_URL = 2048
    private const val MAX_LOGIN_KEY = 8192
    private const val MAX_SKIPPED_EXTENSIONS = 32
    private const val MAX_EXTENSION_NAME = 80

    private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val PIN = Regex("[0-9a-f]{64}")
    private val LOCK_TIMEOUTS = setOf(15L, 60L, 300L)
    private val THEME_MODES = setOf("System", "Light", "Dark")

    private val ROOT_FIELDS = setOf("format", "version", "profiles", "preferences", "extensions")
    private val PROFILE_FIELDS = setOf(
        "id", "name", "serverUrl", "onboardingCompleted", "pinnedSpkiSha256",
        "customShortcuts", "favoriteCommands", "favorites", "loginKey",
    )
    private val PREFERENCE_FIELDS = setOf(
        "blockScreenshots", "lockTimeoutSeconds", "stylusPrecisionEnabled", "stylusHintSeen",
        "themeMode", "dynamicColor", "hideDevicesWhenLocked",
    )
    private val SHORTCUT_FIELDS = setOf("label", "key", "ctrl", "alt", "shift", "win")
    private val COMMAND_FIELDS = setOf("name", "command")
    private val FAVORITE_FIELDS = setOf("name", "url")

    fun encode(backup: SettingsBackup, includeLoginKeys: Boolean = false): ByteArray {
        if (backup.profiles.size > MAX_PROFILES) fail(BackupFailureReason.Limit)
        validatePreferences(backup.preferences)

        val profiles = JSONArray()
        val ids = HashSet<String>()
        for (entry in backup.profiles) {
            validateProfile(entry.profile, entry.favorites)
            if (!ids.add(entry.profile.id)) fail(BackupFailureReason.Corrupt)
            if (includeLoginKeys) {
                entry.loginKey?.let { requireText(it, "loginKey", MAX_LOGIN_KEY) }
            }
            profiles.put(encodeProfile(entry, includeLoginKeys))
        }

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("profiles", profiles)
            .put("preferences", encodePreferences(backup.preferences))
            .put("extensions", JSONObject())

        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BYTES) fail(BackupFailureReason.Limit)
        return bytes
    }

    fun decode(bytes: ByteArray, allowLoginKeys: Boolean = false): SettingsBackup {
        if (bytes.size > MAX_BYTES) fail(BackupFailureReason.Limit)
        return try {
            decodeRoot(parseObject(bytes), allowLoginKeys)
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

    private fun decodeRoot(root: JSONObject, allowLoginKeys: Boolean): SettingsBackup {
        // Check the format and version before the field allowlist so a genuinely newer document
        // is reported as NewerVersion even when it already contains new fields.
        if (requiredString(root, "format") != FORMAT) fail(BackupFailureReason.Corrupt)
        val version = requiredLong(root, "version")
        if (version > VERSION.toLong()) fail(BackupFailureReason.NewerVersion)
        if (version != VERSION.toLong()) fail(BackupFailureReason.Corrupt)
        requireOnly(root, ROOT_FIELDS)

        val profileArray = requiredArray(root, "profiles")
        if (profileArray.length() > MAX_PROFILES) fail(BackupFailureReason.Limit)
        val seenIds = HashSet<String>()
        val profiles = ArrayList<BackupProfile>(profileArray.length())
        for (index in 0 until profileArray.length()) {
            val item = profileArray.optJSONObject(index) ?: fail(BackupFailureReason.Corrupt)
            profiles += decodeProfile(item, allowLoginKeys, seenIds)
        }

        if (!root.has("preferences")) fail(BackupFailureReason.Corrupt)
        val preferences = decodePreferences(root.get("preferences"))
        val skippedExtensions = decodeExtensions(root)
        return SettingsBackup(profiles, preferences, skippedExtensions)
    }

    /**
     * All extension sections are currently unknown (including `portForwards`), so their names are
     * reported and their values are ignored. Values may be any JSON value; only their existence
     * and key names are bounded here.
     */
    private fun decodeExtensions(root: JSONObject): Set<String> {
        if (!root.has("extensions")) return emptySet()
        val extensions = root.get("extensions")
        if (extensions !is JSONObject) fail(BackupFailureReason.Corrupt)
        if (extensions.length() > MAX_SKIPPED_EXTENSIONS) fail(BackupFailureReason.Limit)
        val skipped = linkedSetOf<String>()
        val keys = extensions.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name.length > MAX_EXTENSION_NAME) fail(BackupFailureReason.Limit)
            if (name.isBlank() || !isSafeText(name)) fail(BackupFailureReason.Corrupt)
            skipped += name
        }
        return skipped
    }

    private fun decodeProfile(
        item: JSONObject,
        allowLoginKeys: Boolean,
        seenIds: MutableSet<String>,
    ): BackupProfile {
        if (!allowLoginKeys && item.has("loginKey")) fail(BackupFailureReason.Corrupt)
        requireOnly(item, PROFILE_FIELDS)

        val id = requiredString(item, "id")
        if (!PROFILE_ID.matches(id)) fail(BackupFailureReason.Corrupt)
        if (!seenIds.add(id)) fail(BackupFailureReason.Corrupt)

        val name = requiredString(item, "name")
        requireText(name, "profile.name", MAX_PROFILE_NAME)

        val serverUrl = requiredString(item, "serverUrl")
        validateServerUrl(serverUrl)

        val onboardingCompleted = requiredBoolean(item, "onboardingCompleted")
        val pin = optionalString(item, "pinnedSpkiSha256")
        if (pin != null && !PIN.matches(pin)) fail(BackupFailureReason.Corrupt)

        val shortcuts = decodeShortcuts(requiredArray(item, "customShortcuts"))
        val commands = decodeCommands(requiredArray(item, "favoriteCommands"))
        val favorites = decodeFavorites(requiredArray(item, "favorites"))

        val loginKey = if (allowLoginKeys) {
            optionalString(item, "loginKey")?.also { requireText(it, "loginKey", MAX_LOGIN_KEY) }
        } else {
            null
        }

        return BackupProfile(
            profile = ServerProfile(
                id = id,
                name = name,
                serverUrl = serverUrl,
                onboardingCompleted = onboardingCompleted,
                customShortcuts = shortcuts,
                favoriteCommands = commands,
                pinnedSpkiSha256 = pin,
            ),
            favorites = favorites,
            loginKey = loginKey,
        )
    }

    private fun decodeShortcuts(array: JSONArray): List<CustomShortcut> {
        if (array.length() > CustomShortcut.MAX_SHORTCUTS) fail(BackupFailureReason.Limit)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: fail(BackupFailureReason.Corrupt)
                requireOnly(item, SHORTCUT_FIELDS)
                val label = requiredString(item, "label")
                requireText(label, "custom shortcut label", CustomShortcut.MAX_LABEL)
                val key = requiredString(item, "key")
                if (key !in CustomShortcut.keys) fail(BackupFailureReason.Corrupt)
                add(
                    CustomShortcut(
                        label = label,
                        key = key,
                        ctrl = requiredBoolean(item, "ctrl"),
                        alt = requiredBoolean(item, "alt"),
                        shift = requiredBoolean(item, "shift"),
                        win = requiredBoolean(item, "win"),
                    ),
                )
            }
        }
    }

    private fun decodeCommands(array: JSONArray): List<FavoriteCommand> {
        if (array.length() > FavoriteCommand.LIMIT) fail(BackupFailureReason.Limit)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: fail(BackupFailureReason.Corrupt)
                requireOnly(item, COMMAND_FIELDS)
                val name = requiredString(item, "name")
                val command = requiredString(item, "command")
                requireText(name, "favorite command name", 60)
                requireText(command, "favorite command", 4096)
                add(FavoriteCommand(name, command))
            }
        }
    }

    private fun decodeFavorites(array: JSONArray): List<FavoriteLink> {
        if (array.length() > MAX_FAVORITES) fail(BackupFailureReason.Limit)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: fail(BackupFailureReason.Corrupt)
                requireOnly(item, FAVORITE_FIELDS)
                val name = requiredString(item, "name")
                val url = requiredString(item, "url")
                requireText(name, "favorite name", MAX_FAVORITE_NAME)
                validateFavoriteTarget(url)
                add(FavoriteLink(name, url))
            }
        }
    }

    private fun decodePreferences(value: Any?): BackupPreferences {
        val item = value as? JSONObject ?: fail(BackupFailureReason.Corrupt)
        requireOnly(item, PREFERENCE_FIELDS)
        val lockTimeout = requiredLong(item, "lockTimeoutSeconds")
        if (lockTimeout !in LOCK_TIMEOUTS) fail(BackupFailureReason.Corrupt)
        val themeMode = requiredString(item, "themeMode")
        if (themeMode !in THEME_MODES) fail(BackupFailureReason.Corrupt)
        return BackupPreferences(
            blockScreenshots = requiredBoolean(item, "blockScreenshots"),
            lockTimeoutSeconds = lockTimeout,
            stylusPrecisionEnabled = requiredBoolean(item, "stylusPrecisionEnabled"),
            stylusHintSeen = requiredBoolean(item, "stylusHintSeen"),
            themeMode = themeMode,
            dynamicColor = requiredBoolean(item, "dynamicColor"),
            // Optional for old v1 documents that predate the lock-screen device preference.
            hideDevicesWhenLocked = optionalBoolean(item, "hideDevicesWhenLocked", false),
        )
    }

    private fun encodeProfile(entry: BackupProfile, includeLoginKeys: Boolean): JSONObject {
        val profile = entry.profile
        return JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("serverUrl", profile.serverUrl)
            .put("onboardingCompleted", profile.onboardingCompleted)
            .put("pinnedSpkiSha256", profile.pinnedSpkiSha256 ?: JSONObject.NULL)
            .put("customShortcuts", encodeShortcuts(profile.customShortcuts))
            .put("favoriteCommands", encodeCommands(profile.favoriteCommands))
            .put("favorites", encodeFavorites(entry.favorites))
            .apply {
                if (includeLoginKeys && entry.loginKey != null) put("loginKey", entry.loginKey)
            }
    }

    private fun encodeShortcuts(items: List<CustomShortcut>): JSONArray = JSONArray().apply {
        items.forEach { shortcut ->
            put(
                JSONObject()
                    .put("label", shortcut.label)
                    .put("key", shortcut.key)
                    .put("ctrl", shortcut.ctrl)
                    .put("alt", shortcut.alt)
                    .put("shift", shortcut.shift)
                    .put("win", shortcut.win),
            )
        }
    }

    private fun encodeCommands(items: List<FavoriteCommand>): JSONArray = JSONArray().apply {
        items.forEach { command ->
            put(JSONObject().put("name", command.name).put("command", command.command))
        }
    }

    private fun encodeFavorites(items: List<FavoriteLink>): JSONArray = JSONArray().apply {
        items.forEach { favorite ->
            put(JSONObject().put("name", favorite.name).put("url", favorite.url))
        }
    }

    private fun encodePreferences(preferences: BackupPreferences): JSONObject = JSONObject()
        .put("blockScreenshots", preferences.blockScreenshots)
        .put("lockTimeoutSeconds", preferences.lockTimeoutSeconds)
        .put("stylusPrecisionEnabled", preferences.stylusPrecisionEnabled)
        .put("stylusHintSeen", preferences.stylusHintSeen)
        .put("themeMode", preferences.themeMode)
        .put("dynamicColor", preferences.dynamicColor)
        .put("hideDevicesWhenLocked", preferences.hideDevicesWhenLocked)

    private fun validatePreferences(preferences: BackupPreferences) {
        if (preferences.lockTimeoutSeconds !in LOCK_TIMEOUTS) fail(BackupFailureReason.Corrupt)
        if (preferences.themeMode !in THEME_MODES) fail(BackupFailureReason.Corrupt)
    }

    private fun validateProfile(profile: ServerProfile, favorites: List<FavoriteLink>) {
        if (!PROFILE_ID.matches(profile.id)) fail(BackupFailureReason.Corrupt)
        requireText(profile.name, "profile.name", MAX_PROFILE_NAME)
        validateServerUrl(profile.serverUrl)
        profile.pinnedSpkiSha256?.let { if (!PIN.matches(it)) fail(BackupFailureReason.Corrupt) }
        validateShortcuts(profile.customShortcuts)
        validateCommands(profile.favoriteCommands)
        if (favorites.size > MAX_FAVORITES) fail(BackupFailureReason.Limit)
        favorites.forEach { favorite ->
            requireText(favorite.name, "favorite name", MAX_FAVORITE_NAME)
            validateFavoriteTarget(favorite.url)
        }
    }

    private fun validateShortcuts(items: List<CustomShortcut>) {
        if (items.size > CustomShortcut.MAX_SHORTCUTS) fail(BackupFailureReason.Limit)
        items.forEach { shortcut ->
            requireText(shortcut.label, "custom shortcut label", CustomShortcut.MAX_LABEL)
            if (shortcut.key !in CustomShortcut.keys) fail(BackupFailureReason.Corrupt)
        }
    }

    private fun validateCommands(items: List<FavoriteCommand>) {
        if (items.size > FavoriteCommand.LIMIT) fail(BackupFailureReason.Limit)
        items.forEach { command ->
            requireText(command.name, "favorite command name", 60)
            requireText(command.command, "favorite command", 4096)
        }
    }

    private fun validateServerUrl(url: String) {
        requireText(url, "serverUrl", MAX_URL)
        if (url.any { it.isWhitespace() }) fail(BackupFailureReason.Corrupt)
        if (ServerUrlValidator.validate(url, allowHttpInDebug = false) != null) {
            fail(BackupFailureReason.Corrupt)
        }
    }

    /** Current favorites are raw MeshCentral node ids (`node//example`), not web URLs. */
    private fun validateFavoriteTarget(url: String) {
        requireText(url, "favorite.url", MAX_FAVORITE_URL)
        if (url.any { it.isWhitespace() || it == '?' || it == '#' }) fail(BackupFailureReason.Corrupt)
        val rawNode = url.startsWith("node/") && url.length > "node/".length
        if (!rawNode) fail(BackupFailureReason.Corrupt)
    }

    private fun requireText(value: String, field: String, maxLength: Int) {
        if (value.length > maxLength) fail(BackupFailureReason.Limit)
        if (value.isBlank()) fail(BackupFailureReason.Corrupt)
        if (!isSafeText(value)) fail(BackupFailureReason.Corrupt)
    }

    private fun isSafeText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.code <= 0x1F || char.code in 0x7F..0x9F || char == '\u2028' || char == '\u2029') {
                return false
            }
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
                continue
            }
            if (Character.isLowSurrogate(char)) return false
            index++
        }
        return true
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

    private fun optionalString(obj: JSONObject, key: String): String? {
        if (!obj.has(key)) return null
        val value = obj.get(key)
        if (value === JSONObject.NULL) return null
        if (value !is String) fail(BackupFailureReason.Corrupt)
        return value
    }

    private fun requiredBoolean(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key)) fail(BackupFailureReason.Corrupt)
        val value = obj.get(key)
        if (value !is Boolean) fail(BackupFailureReason.Corrupt)
        return value
    }

    private fun optionalBoolean(obj: JSONObject, key: String, default: Boolean): Boolean {
        if (!obj.has(key)) return default
        val value = obj.get(key)
        if (value !is Boolean) fail(BackupFailureReason.Corrupt)
        return value
    }

    private fun requiredLong(obj: JSONObject, key: String): Long {
        if (!obj.has(key)) fail(BackupFailureReason.Corrupt)
        val value = obj.get(key)
        if (value !is Int && value !is Long) fail(BackupFailureReason.Corrupt)
        return (value as Number).toLong()
    }

    private fun requiredArray(obj: JSONObject, key: String): JSONArray {
        if (!obj.has(key)) fail(BackupFailureReason.Corrupt)
        val value = obj.get(key)
        if (value !is JSONArray) fail(BackupFailureReason.Corrupt)
        return value
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
