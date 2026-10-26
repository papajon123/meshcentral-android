package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/** Form content scrolls independently; actions stay inside this window's keyboard-safe area. */
@Composable
internal fun ParityFormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    // Compose 1.7.6's non-default-width DialogLayout measures against screenHeightDp,
    // ignoring the resized window height. Keep the platform measurement constraints,
    // but handle insets explicitly: floating-window resize is not enough on Android 15+.
    // Dialog supplies ADJUST_RESIZE on older Android versions for inset dispatch.
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            // Compose 1.7.6 leaves the modern soft-input mode unchanged. Prevent the
            // platform from panning the entire dialog to reveal a tall focused field.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            // A landscape phone can have little more than a text field's height above
            // the IME. Let the title scroll too and reserve the remaining space for actions.
            val compactHeight = maxHeight < 300.dp
            val gutter = if (compactHeight) MeshTheme.spacing.xs else MeshTheme.spacing.lg
            val gap = if (compactHeight) MeshTheme.spacing.xs else MeshTheme.spacing.md
            Surface(
                modifier = Modifier.padding(gutter).widthIn(max = 640.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.padding(horizontal = MeshTheme.spacing.lg, vertical = gutter),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    if (!compactHeight) ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
                            if (compactHeight) ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
