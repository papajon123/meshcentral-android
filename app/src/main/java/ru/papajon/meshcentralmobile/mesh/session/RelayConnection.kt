package ru.papajon.meshcentralmobile.mesh.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.papajon.meshcentralmobile.localization.UiText

enum class RelayProtocol(val usage: Int) {
    AgentTerminal(1), AgentFiles(5), SshTerminal(11), SshFiles(13),
}

enum class RelayPhase { Idle, Connecting, Waiting, Connected, Closed, Failed }

data class RelayStatus(
    val phase: RelayPhase = RelayPhase.Idle,
    val message: UiText? = null,
    val recorded: Boolean = false,
)

sealed interface RelayFrame {
    data class Text(val value: String) : RelayFrame
    data class Binary(val value: ByteArray) : RelayFrame
}

/** A single-consumer, bounded connection. A new session never replays previous input. */
interface RelayConnection {
    val protocol: RelayProtocol
    val state: StateFlow<RelayStatus>
    val incoming: Flow<RelayFrame>
    fun connect()
    fun sendText(value: String): Boolean
    fun sendBinary(value: ByteArray): Boolean
    fun close()
}
