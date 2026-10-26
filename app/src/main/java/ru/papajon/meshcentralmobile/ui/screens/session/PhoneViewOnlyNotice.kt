package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.PhoneRemoteInput

@Composable
fun PhoneViewOnlyNotice(device: MeshDevice, modifier: Modifier = Modifier) {
    if (!device.isAndroidAgent || device.phoneInputAllowed) return
    val reason = when (device.remoteInput) {
        PhoneRemoteInput.Disabled -> R.string.phone_control_disabled
        PhoneRemoteInput.Unsupported -> R.string.phone_control_unsupported
        PhoneRemoteInput.Unknown -> R.string.phone_control_unknown
        PhoneRemoteInput.Enabled -> R.string.phone_control_no_permission
    }
    Surface(modifier.fillMaxWidth().testTag("phone_view_only"),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Text(stringResource(reason), Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}
