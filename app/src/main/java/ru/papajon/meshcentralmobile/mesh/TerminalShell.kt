package ru.papajon.meshcentralmobile.mesh

/**
 * Agent terminal flavours, as numbered by MeshCentral 1.3.1 `agents/meshcore.js:3564`:
 * 1 = Terminal (admin), 6 = PowerShell (admin), 8 = Terminal (user), 9 = PowerShell (user).
 *
 * The number travels as the relay `p` parameter, the tunnel request and the first relay frame.
 * PowerShell exists only on Windows agents (`meshcore.js:3627` rejects it elsewhere); the user
 * terminal runs as the logged-in console user on Windows and Linux (`meshcore.js:3017`).
 */
enum class TerminalShell(val usage: Int, val windowsOnly: Boolean) {
    Default(1, false),
    PowerShell(6, true),
    User(8, false),
    UserPowerShell(9, true);

    companion object {
        val usages: Set<Int> = entries.map { it.usage }.toSet()

        fun available(device: MeshDevice): List<TerminalShell> = when {
            device.isAndroidAgent -> listOf(Default)
            device.isWindows -> entries
            device.isLinux -> listOf(Default, User)
            else -> listOf(Default)
        }

        fun fromName(name: String?): TerminalShell = entries.firstOrNull { it.name == name } ?: Default
    }
}
