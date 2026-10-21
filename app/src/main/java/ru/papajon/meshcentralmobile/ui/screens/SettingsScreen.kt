package ru.papajon.meshcentralmobile.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.components.StatusPill
import ru.papajon.meshcentralmobile.ui.theme.MeshMono
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape
import ru.papajon.meshcentralmobile.ui.web.MeshWebViewSurface

/** The only update channel of the public build: signed APKs published with GitHub releases. */
private const val RELEASES_URL = "https://github.com/papajon123/meshcentral-android/releases"

/** Auto-lock choices, identical to the ones the original screen offered. */
private val LockTimeoutOptions = listOf(
    15L to R.string.settings_auto_lock_option_15,
    60L to R.string.settings_auto_lock_option_60,
    300L to R.string.settings_auto_lock_option_300,
)

/**
 * Settings grouped into cards instead of a column of equal buttons: read-only server identity,
 * security switches, PIN management, the lock action, the destructive data wipe and the debug
 * entry point. Exactly one filled button exists (Set PIN); everything else is a switch, a pill or a
 * text action, and the destructive action is last inside its own card.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    serverUrl: String,
    hasPin: Boolean,
    onScreenshotBlockingChanged: (Boolean) -> Unit,
    onBiometricUnlockChanged: (Boolean) -> Unit,
    onLockTimeoutChanged: (Long) -> Unit,
    onPinSaved: (String) -> Unit,
    onPinCleared: () -> Unit,
    onLockNow: () -> Unit,
    onCredentialsCleared: () -> Unit,
    onMeshSessionCleared: () -> Unit,
    onWebViewSessionCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newPin by rememberSaveable { mutableStateOf("") }
    val initialMessage = stringResource(
        if (hasPin) R.string.settings_message_pin_configured else R.string.settings_message_pin_missing,
    )
    var message by rememberSaveable { mutableStateOf(initialMessage) }
    var showDebugWebView by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    if (showDebugWebView) {
        DebugWebViewOverlay(
            serverUrl = serverUrl,
            onClose = { showDebugWebView = false },
            modifier = modifier,
        )
        return
    }

    val spacing = MeshTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Column(
            modifier = Modifier.padding(top = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsStatusStrip(message = message)

        SettingsSection(
            title = stringResource(R.string.settings_section_updates),
            subtitle = stringResource(R.string.settings_section_updates_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        IconBadge(
                            icon = Icons.Outlined.SystemUpdateAlt,
                            container = MaterialTheme.colorScheme.primaryContainer,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_update_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_update_supporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val releases = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                            val opened = try {
                                context.startActivity(releases)
                                true
                            } catch (_: ActivityNotFoundException) {
                                false
                            }
                            if (!opened) {
                                message = context.getString(R.string.settings_update_unavailable)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = PillShape,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_update_action),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_server),
            subtitle = stringResource(R.string.settings_section_server_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    IconBadge(
                        icon = Icons.Outlined.Dns,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_server_name),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = serverUrl,
                            style = MeshMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_security),
            subtitle = stringResource(R.string.settings_section_security_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                Column {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Security,
                        title = stringResource(R.string.settings_block_screenshots),
                        supporting = stringResource(R.string.settings_block_screenshots_supporting),
                        checked = settings.blockScreenshots,
                        onCheckedChange = onScreenshotBlockingChanged,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = stringResource(R.string.settings_biometric_unlock),
                        supporting = stringResource(R.string.settings_biometric_unlock_supporting),
                        checked = settings.biometricUnlockEnabled,
                        onCheckedChange = {
                            onBiometricUnlockChanged(it)
                            message = if (it) {
                                context.getString(R.string.settings_biometric_enabled)
                            } else {
                                context.getString(R.string.settings_biometric_disabled)
                            }
                        },
                    )
                    SettingsDivider()
                    SettingsAutoLockRow(
                        currentSeconds = settings.lockTimeoutSeconds,
                        onLockTimeoutChanged = onLockTimeoutChanged,
                    )
                }
            }

            MeshCard(shape = MaterialTheme.shapes.large) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.lg, vertical = spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        IconBadge(
                            icon = Icons.Outlined.Key,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_pin_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (hasPin) {
                                    stringResource(R.string.settings_pin_configured)
                                } else {
                                    stringResource(R.string.settings_pin_missing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            text = if (hasPin) {
                                stringResource(R.string.settings_pin_status_set)
                            } else {
                                stringResource(R.string.settings_pin_status_missing)
                            },
                            color = if (hasPin) MeshTheme.colors.online else MeshTheme.colors.offline,
                        )
                    }

                    SettingsDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.lg, vertical = spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = newPin,
                            onValueChange = { newPin = it.filter(Char::isDigit).take(12) },
                            label = { Text(stringResource(R.string.settings_pin_new)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            supportingText = { Text(stringResource(R.string.settings_pin_new_supporting)) },
                        )
                        Button(
                            onClick = {
                                onPinSaved(newPin)
                                newPin = ""
                                message = context.getString(R.string.settings_pin_saved)
                            },
                            enabled = newPin.length >= 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = PillShape,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                            Text(text = stringResource(R.string.settings_pin_save), style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    SettingsDivider()

                    SettingsActionRow(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.settings_pin_remove),
                        supporting = stringResource(R.string.settings_pin_remove_supporting),
                        actionLabel = stringResource(R.string.settings_pin_remove_action),
                        enabled = hasPin,
                        destructive = true,
                        onClick = {
                            onPinCleared()
                            message = context.getString(R.string.settings_pin_cleared)
                        },
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_session),
            subtitle = stringResource(R.string.settings_section_session_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                SettingsActionRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.settings_lock_now),
                    supporting = stringResource(R.string.settings_lock_now_supporting),
                    actionLabel = stringResource(R.string.settings_lock_now_action),
                    onClick = onLockNow,
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_data),
            subtitle = stringResource(R.string.settings_section_data_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                SettingsActionRow(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    title = stringResource(R.string.settings_clear_session),
                    supporting = stringResource(R.string.settings_clear_session_supporting),
                    actionLabel = stringResource(R.string.settings_clear_session_action),
                    destructive = true,
                    onClick = {
                        onCredentialsCleared()
                        onMeshSessionCleared()
                        onWebViewSessionCleared()
                        message = context.getString(R.string.settings_session_cleared)
                    },
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_about),
            subtitle = stringResource(R.string.settings_section_about_subtitle),
        ) {
            MeshCard(shape = MaterialTheme.shapes.large) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.lg, vertical = spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        IconBadge(
                            icon = Icons.Outlined.Info,
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME) +
                                    if (BuildConfig.DEBUG) {
                                        stringResource(R.string.settings_version_debug_suffix)
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SettingsDivider()

                    SettingsActionRow(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_debug_webview),
                        supporting = stringResource(R.string.settings_debug_webview_supporting),
                        actionLabel = stringResource(R.string.settings_debug_webview_action),
                        onClick = { showDebugWebView = true },
                    )
                }
            }
        }
    }
}

/** Section title plus the cards that belong to it, so every screen groups the same way. */
@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MeshTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(title = title, subtitle = subtitle)
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            content = content,
        )
    }
}

/** Hairline separator between rows inside a card, inset past the leading icon badge. */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Read-only notice strip for the screen's status message. */
@Composable
private fun SettingsStatusStrip(message: String, modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Switch row: the whole row is the toggle target, so the hit area is far larger than the switch. */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = MeshTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) MeshTheme.motion.pressScale else 1f,
        animationSpec = MeshTheme.motion.effectFast,
        label = "settings-row-press",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        IconBadge(icon = icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

/** Action row: title and explanation on the left, a single text action on the right. */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        IconBadge(
            icon = icon,
            tint = if (destructive) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            container = if (destructive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = PillShape,
            colors = if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
        ) {
            Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Auto-lock row: current value in words plus a three-way pill selector. */
@Composable
private fun SettingsAutoLockRow(
    currentSeconds: Long,
    onLockTimeoutChanged: (Long) -> Unit,
) {
    val spacing = MeshTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBadge(
                icon = Icons.Outlined.Timer,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_auto_lock),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_auto_lock_supporting, lockTimeoutLabel(currentSeconds)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LockTimeoutOptions.forEach { (seconds, labelRes) ->
                TimeoutPill(
                    label = stringResource(labelRes),
                    selected = currentSeconds == seconds,
                    onClick = { onLockTimeoutChanged(seconds) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TimeoutPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "timeout-pill-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "timeout-pill-content",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        },
        animationSpec = MeshTheme.motion.effectColor,
        label = "timeout-pill-border",
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(container)
            .border(border = BorderStroke(1.dp, borderColor), shape = PillShape)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/** Full-screen raw WebView kept behind the About card; the close action returns to the settings list. */
@Composable
private fun DebugWebViewOverlay(
    serverUrl: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.sm, top = spacing.md, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBadge(icon = Icons.Outlined.BugReport, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_debug_webview),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = serverUrl,
                    style = MeshMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = PillShape,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.common_action_close), style = MaterialTheme.typography.labelLarge)
            }
        }
        MeshWebViewSurface(
            serverUrl = serverUrl,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun lockTimeoutLabel(seconds: Long): String = when (seconds) {
    15L -> stringResource(R.string.settings_auto_lock_seconds_15)
    60L -> stringResource(R.string.settings_auto_lock_minute_1)
    300L -> stringResource(R.string.settings_auto_lock_minutes_5)
    else -> stringResource(R.string.settings_auto_lock_seconds, seconds)
}
