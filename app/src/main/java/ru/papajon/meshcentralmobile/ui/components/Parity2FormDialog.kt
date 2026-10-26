package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

private val LocalCompactForm = staticCompositionLocalOf { false }

/** A 48dp single-line editor fits beside the 48dp action row in the smallest landscape IME window. */
@Composable
internal fun Parity2TextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String? = null) {
    val compact = LocalCompactForm.current
    // Keep one editor identity across IME resize so the focused input connection is not replaced.
    BasicTextField(value, onValueChange, singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = { editor ->
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else MeshTheme.spacing.xs)) {
                if (!compact) Text(label, style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .padding(horizontal = MeshTheme.spacing.sm, vertical = MeshTheme.spacing.xs), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder ?: label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    editor()
                }
            }
        })
}

/** Reserve actions even in a short landscape window with the IME; the heading may scroll. */
@Composable
internal fun Parity2FormDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = true)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 320.dp
            val padding = if (compact) 0.dp else MeshTheme.spacing.lg
            Surface(Modifier.padding(padding).widthIn(max = 640.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(padding)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
                        // In the smallest IME-resized window, actions already name the operation.
                        // Prioritize the editable field over a repeated heading.
                        if (!compact) ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                        CompositionLocalProvider(LocalCompactForm provides compact) {
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
