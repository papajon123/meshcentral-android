package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.mesh.TwoFingerAction

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopExtraOptions(state: RemoteDesktopState, onScaling: (Int) -> Unit,
    twoFingerAction: TwoFingerAction, onTwoFingerAction: (TwoFingerAction) -> Unit,
    pinchEnabled: Boolean, onPinchEnabled: (Boolean) -> Unit,
    pipSupported: Boolean, pipAutomatic: Boolean, onPipAutomatic: (Boolean) -> Unit, onEnterPip: () -> Unit,
    hardwareKeyboardCaptured: Boolean, onHardwareKeyboardCaptured: (Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.desktop_scale, state.quality.scaling * 100 / 1024), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(256, 512, 768, 1024).forEach { value ->
                FilterChip(selected = state.quality.scaling == value, onClick = { onScaling(value) },
                    label = { Text("${value * 100 / 1024}%") }, modifier = Modifier.testTag("desktop_scale_$value"))
            }
        }
        Text(sessionStreamRate(state.framesPerSecond, state.bytesPerSecond),
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.desktop_two_fingers), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TwoFingerAction.entries.forEach { action ->
                FilterChip(selected = twoFingerAction == action, onClick = { onTwoFingerAction(action) },
                    label = { Text(stringResource(when(action) {
                        TwoFingerAction.Default -> R.string.desktop_gesture_default
                        TwoFingerAction.Scroll -> R.string.desktop_gesture_scroll
                        TwoFingerAction.Pan -> R.string.desktop_gesture_pan
                    })) }, modifier = Modifier.testTag("desktop_two_finger_${action.name}"))
            }
        }
        FilterChip(selected = pinchEnabled, onClick = { onPinchEnabled(!pinchEnabled) },
            label = { Text(stringResource(R.string.desktop_pinch)) }, modifier = Modifier.testTag("desktop_pinch"))
        Text(stringResource(R.string.desktop_three_fingers), style = MaterialTheme.typography.bodySmall)
        if (pipSupported) {
            HorizontalDivider()
            FilterChip(selected = pipAutomatic, onClick = { onPipAutomatic(!pipAutomatic) },
                label = { Text(stringResource(R.string.desktop_pip_auto)) }, modifier = Modifier.testTag("desktop_pip_auto"))
            OutlinedButton(onClick = onEnterPip, enabled = state.hasFrame,
                modifier = Modifier.testTag("desktop_enter_pip")) { Text(stringResource(R.string.desktop_pip)) }
        }
        if (state.device?.isAndroidAgent != true) {
            FilterChip(selected = hardwareKeyboardCaptured, onClick = { onHardwareKeyboardCaptured(!hardwareKeyboardCaptured) },
                label = { Text(stringResource(R.string.desktop_keyboard_capture)) }, modifier = Modifier.testTag("desktop_keyboard_capture"))
            Text(stringResource(R.string.desktop_keyboard_release), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.desktop_input_help), style = MaterialTheme.typography.bodySmall)
        }
    }
}
