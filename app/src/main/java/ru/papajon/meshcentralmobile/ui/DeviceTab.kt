package ru.papajon.meshcentralmobile.ui

import androidx.annotation.StringRes
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol

enum class DeviceTab(@StringRes val labelRes: Int, val protocol: RelayProtocol? = null) {
    Overview(R.string.device_overview),
    Desktop(R.string.device_desktop),
    Terminal(R.string.device_terminal, RelayProtocol.AgentTerminal),
    Files(R.string.device_files, RelayProtocol.AgentFiles),
    Ssh(R.string.device_ssh, RelayProtocol.SshTerminal),
    Sftp(R.string.device_sftp, RelayProtocol.SshFiles),
    System(R.string.workspace_system),
    Help(R.string.workspace_help),
}
