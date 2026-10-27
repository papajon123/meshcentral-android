package ru.papajon.meshcentralmobile.mesh.files

import org.json.JSONObject

data class RemoteFile(val name: String, val type: Int, val size: Long = 0, val sizeKnown: Boolean = true,
    val listingIndex: Int? = null, val ambiguousName: Boolean = false, val platformSafe: Boolean = true) {
    val directory: Boolean get() = type == 1 || type == 2
    val actionable: Boolean get() = platformSafe && !ambiguousName && AgentFilesProtocol.usableExistingName(name, type) && (directory || sizeKnown)
    /** Escape control/format characters so an unsupported entry cannot forge lines or bidi labels. */
    val displayName: String get() = buildString {
        name.take(1024).forEach { character ->
            if (AgentFilesProtocol.unsafeControl(character)) append("\\u" + character.code.toString(16).padStart(4, '0'))
            else append(character)
        }
        if (name.length > 1024) append('…')
    }
}

/** MeshCentral 1.2.6 agents/meshcore.js, getDirectoryInfo and protocol 5.
 * This is deliberately NOT the SSH/SFTP adapter: its acknowledgements differ.
 */
internal object AgentFilesProtocol {
    const val CHUNK_SIZE = 16_380
    const val MAX_LIST_ENTRIES = 20_000
    const val MAX_JSON_BYTES = 1024 * 1024

    fun jsonWithinLimit(value: String): Boolean = value.length <= MAX_JSON_BYTES &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES

    fun validName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.length <= 255 && name.none { it == '/' || it == '\\' || it == ':' || unsafeControl(it) }

    fun unsafeControl(character: Char): Boolean = Character.isISOControl(character) ||
        Character.getType(character) == Character.FORMAT.toInt()

    private fun driveName(name: String, type: Int): Boolean = type == 1 && Regex("^[A-Za-z]:[/\\\\]?$").matches(name)

    /** Existing POSIX components may contain ':' and whitespace; never apply creation rules here. */
    fun usableExistingName(name: String, type: Int): Boolean = driveName(name, type) ||
        (name.isNotEmpty() && name != "." && name != ".." && name.length <= 1024 &&
            name.none { it == '/' || it == '\\' || unsafeControl(it) })

    fun join(parent: String, name: String): String = when {
        parent.isEmpty() -> if (Regex("^[A-Za-z]:[/\\\\]?$").matches(name)) name.trimEnd('/', '\\') + "/" else name
        else -> parent.trimEnd('/', '\\') + "/" + name
    }

    fun parent(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty() || Regex("^[A-Za-z]:$").matches(normalized)) return ""
        val index = normalized.lastIndexOf('/')
        if (index < 0) return "" // Android virtual categories (Images, Sdcard, Audio, Videos).
        return if (index <= 0) "/" else normalized.substring(0, index).let {
            if (Regex("^[A-Za-z]:$").matches(it)) "$it/" else it
        }
    }

    fun isMediaCategory(path: String): Boolean = path in setOf("Images", "Audio", "Videos")

    fun entries(message: JSONObject, phoneMedia: Boolean = false, requestedPath: String = message.optString("path")): List<RemoteFile> {
        val array = requireNotNull(message.optJSONArray("dir")) { "Directory unavailable" }
        require(array.length() <= MAX_LIST_ENTRIES) { "Directory limit" }
        val entries = ArrayList<RemoteFile>(array.length())
        val names = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            // Android getString also coerces JSON null to "null". A missing filename cannot
            // identify a remote file; reject it rather than offering actions on a fake name.
            val name = requireNotNull(item.opt("n") as? String) { "Missing filename" }
            if (name == "." || name == "..") continue
            val type = item.getInt("t")
            val validDrive = driveName(name, type)
            // Slash/NUL are not real filename components, and duplicate names make identity ambiguous.
            // Backslash and other controls can exist on POSIX: keep them visible but inoperable.
            require(name.isNotEmpty() && '\u0000' !in name && (validDrive || '/' !in name) &&
                (names.add(name) || phoneMedia) && type in 1..3)
            val rawSize = item.opt("s")
            val size = (rawSize as? Number)?.toLong() ?: 0
            val sizeKnown = rawSize is Number && size in 0..9_007_199_254_740_991L && rawSize.toString() == size.toString()
            entries += RemoteFile(name, type, if (sizeKnown) size else 0, sizeKnown, if (phoneMedia) i else null,
                platformSafe = validDrive || !(WindowsFileNames.isWindowsPath(requestedPath) || WindowsFileNames.isWindowsPath(message.optString("path"))) || WindowsFileNames.safeComponent(name))
        }
        val duplicates = if (phoneMedia) entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys else emptySet()
        return entries.map { if (it.name in duplicates) it.copy(ambiguousName = true) else it }
            .sortedWith(compareBy<RemoteFile> { !it.directory }.thenBy { it.name.lowercase() })
    }

    /** Always escape; the receiver strips exactly one zero, including for zero or '{' payloads. */
    fun uploadFrame(bytes: ByteArray, count: Int): ByteArray {
        require(count in 1..CHUNK_SIZE && count <= bytes.size)
        return ByteArray(count + 1).also { bytes.copyInto(it, 1, 0, count) }
    }

    fun downloadFinal(frame: ByteArray): Boolean {
        require(frame.size in 4..(CHUNK_SIZE + 4))
        require(frame[0] == 1.toByte() && frame[1] == 0.toByte() && frame[2] == 0.toByte())
        require(frame[3] == 0.toByte() || frame[3] == 1.toByte())
        return frame[3] == 1.toByte()
    }
}
