package ru.papajon.meshcentralmobile.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.LocalMeshAppearance
import ru.papajon.meshcentralmobile.ui.theme.MeshThemeMode

@Composable
internal fun AppearanceSection(modifier: Modifier = Modifier) {
    val controls = LocalMeshAppearance.current ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.settings_appearance_title), subtitle = stringResource(R.string.settings_appearance_subtitle))
        MeshCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BoxWithConstraints {
                    if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.4f) {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MeshThemeMode.entries.forEach { mode ->
                                ThemeChoice(mode, controls.appearance.mode == mode, { controls.setMode(mode) }, Modifier.fillMaxWidth())
                            }
                        }
                    } else Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeshThemeMode.entries.forEach { mode ->
                            ThemeChoice(mode, controls.appearance.mode == mode, { controls.setMode(mode) }, Modifier.weight(1f))
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .toggleable(controls.appearance.dynamicColor, role = Role.Switch, onValueChange = controls.setDynamicColor)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.settings_dynamic_colors), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_dynamic_colors_supporting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = controls.appearance.dynamicColor, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(mode: MeshThemeMode, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val label = stringResource(when (mode) {
        MeshThemeMode.System -> R.string.settings_theme_system
        MeshThemeMode.Light -> R.string.settings_theme_light
        MeshThemeMode.Dark -> R.string.settings_theme_dark
    })
    val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(when (mode) {
            MeshThemeMode.System -> Icons.Outlined.BrightnessAuto
            MeshThemeMode.Light -> Icons.Outlined.LightMode
            MeshThemeMode.Dark -> Icons.Outlined.DarkMode
        }, null, Modifier.size(20.dp), tint = color)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color)
    }
}
