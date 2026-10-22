package ru.papajon.meshcentralmobile.management

/** Power actions that the server and the current account may allow for a node. */
enum class DevicePowerAction { Wake, Restart, Shutdown, Sleep }

/** A service row supplied by the caller after a verified server command. */
data class ManagedService(
    val name: String,
    val displayName: String,
    val state: String,
    val startMode: String? = null,
)

/** A process row supplied by the caller after a verified server command. */
data class ManagedProcess(
    val pid: Long,
    val name: String,
    val cpu: String? = null,
    val memory: String? = null,
)

/** Service transitions that the UI can request; the caller performs and verifies them. */
enum class ServiceAction { Start, Stop, Restart }
