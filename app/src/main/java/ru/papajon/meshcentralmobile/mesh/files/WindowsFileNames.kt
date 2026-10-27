package ru.papajon.meshcentralmobile.mesh.files

/** Win32 path classification and filename rules for listings from Windows agents. */
internal object WindowsFileNames {
    private const val MAX_COMPONENT_LENGTH = 255

    // Drive root ("C:", "C:\") or a drive-rooted path ("C:\dir", "C:/dir").
    private val DRIVE_PATH = Regex("""^[A-Za-z]:(?:[\\/].*)?$""")
    // UNC paths in native and forward-slash forms: \\server\share...
    private val UNC_PATH = Regex("""^[\\/]{2}[^\\/]+[\\/][^\\/]+.*$""")

    private val RESERVED_DEVICE_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    private val SUPERSCRIPT_DIGITS = mapOf('\u00B9' to '1', '\u00B2' to '2', '\u00B3' to '3')

    fun isWindowsPath(path: String): Boolean = path.isNotEmpty() &&
        (DRIVE_PATH.matches(path) || UNC_PATH.matches(path))

    fun safeComponent(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_COMPONENT_LENGTH) return false
        if (name == "." || name == ".." || name.all { it == '.' }) return false
        if (name.last() == '.' || name.last() == ' ') return false
        if (name.any { it in "/\\:*?\"<>|" }) return false
        if (name.any(::unsafeCharacter)) return false
        return !reservedDevice(name)
    }

    private fun unsafeCharacter(character: Char): Boolean =
        Character.isISOControl(character) ||
            Character.getType(character) == Character.FORMAT.toInt()

    /** Win32 treats the stem before the first dot as a device name and folds the superscript aliases. */
    private fun reservedDevice(name: String): Boolean {
        val stem = name.substringBefore('.').trimEnd(' ', '.')
        val normalized = buildString(stem.length) {
            stem.uppercase().forEach { append(SUPERSCRIPT_DIGITS[it] ?: it) }
        }
        return normalized in RESERVED_DEVICE_NAMES
    }
}
