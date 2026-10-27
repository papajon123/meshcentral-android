package ru.papajon.meshcentralmobile.integration

/**
 * Pure launcher-shortcut policy shared by the visible and hidden privacy paths. It never touches
 * Android types so JVM tests can pin the production revocation rules.
 *
 * A shortcut is retained when its id carries a well-formed device token that still belongs to at
 * least one stored profile snapshot. Enabled shortcuts lose clickability as soon as their token is
 * gone; shortcuts that were already disabled are left alone because re-disabling them would only
 * overwrite the reason they were revoked. Disabled retained shortcuts are re-enabled on the next
 * sync.
 */
internal object ShortcutRevocation {
    const val PREFIX = "device-"
    private val TOKEN = Regex("[a-f0-9-]{36}")

    data class Entry(val id: String, val isEnabled: Boolean)

    fun tokenOf(id: String): String? =
        id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.matches(TOKEN) }

    fun isRetained(id: String, retained: Set<String>): Boolean =
        tokenOf(id)?.let { it in retained } == true

    /** Enabled launcher shortcuts whose token is malformed or missing from every retained profile. */
    fun revokedEnabled(entries: Iterable<Entry>, retained: Set<String>): List<String> = entries
        .filter { it.id.startsWith(PREFIX) && it.isEnabled && !isRetained(it.id, retained) }
        .map { it.id }

    /** Retained shortcuts that an earlier revocation or privacy transition left disabled. */
    fun reenabled(entries: Iterable<Entry>, retained: Set<String>): List<String> = entries
        .filter { it.id.startsWith(PREFIX) && !it.isEnabled && isRetained(it.id, retained) }
        .map { it.id }
}
