package ru.papajon.meshcentralmobile.mesh.session

import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities

/** Unknown permits an explicit attempt, with the server retaining final authority. */
enum class SessionAvailability {
    Available, Unknown, Offline, Unsupported, Denied;

    val canAttempt: Boolean get() = this == Available || this == Unknown
    val label: UiText get() = UiText.Resource(when (this) {
        Available -> R.string.relay_available
        Unknown -> R.string.relay_availability_unknown
        Offline -> R.string.common_status_offline
        Unsupported -> R.string.relay_unsupported
        Denied -> R.string.runtime_capability_no_permission
    })
}

internal fun availabilityFor(device: MeshDevice, protocol: RelayProtocol, server: MeshServerCapabilities): SessionAvailability {
    val ssh = protocol == RelayProtocol.SshTerminal || protocol == RelayProtocol.SshFiles
    // Both SSH endpoints enforce NOTERMINAL in apprelays.js (1.2.6:1763,2315).
    val denyBit = if (protocol == RelayProtocol.AgentFiles || protocol == RelayProtocol.AgentFileDownload) MeshRights.NO_FILES else MeshRights.NO_TERMINAL
    val rights = device.rights
    if (rights != null && rights != MeshRights.FULL &&
        ((rights and MeshRights.REMOTE_CONTROL) == 0 || (rights and denyBit) != 0)) return SessionAvailability.Denied
    if (ssh) {
        if (server.sshEnabled == false || (protocol == RelayProtocol.SshFiles && server.sftpEnabled == false) ||
            (protocol == RelayProtocol.SshTerminal && server.sshTerminalEnabled == false)) return SessionAvailability.Unsupported
        if (device.mtype != 0 && device.mtype != 2 && device.mtype != 3) return SessionAvailability.Unsupported
        // A local device (mtype=3) is reached from the server or relay agent, and may have conn=0.
        if (device.mtype == 2 && device.relayNodeId == null && device.connectivityKnown && device.conn and 1 == 0) return SessionAvailability.Offline
        if (server.sshEnabled == null || (protocol == RelayProtocol.SshFiles && server.sftpEnabled == null) ||
            rights == null || device.mtype == 0 || !device.connectivityKnown || device.relayNodeId != null) return SessionAvailability.Unknown
        return SessionAvailability.Available
    }
    if (device.mtype == 3 || (device.mtype != 0 && device.mtype != 2)) return SessionAvailability.Unsupported
    if (device.connectivityKnown && device.conn and 1 == 0) return SessionAvailability.Offline
    val capabilityBit = if (protocol == RelayProtocol.AgentTerminal) 2 else 4
    if (device.agentCaps != null && device.agentCaps and capabilityBit == 0) return SessionAvailability.Unsupported
    if (rights == null || device.agentCaps == null || device.agentId == null || !device.connectivityKnown) return SessionAvailability.Unknown
    return SessionAvailability.Available
}
