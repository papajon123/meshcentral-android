package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.settings.ServerUrlValidator
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/**
 * Server onboarding form: a compact server identity, one bounded endpoint group, inline validation,
 * one primary save action and the debug-only transport options as a clearly secondary section.
 *
 * Validation remains centralized in [ServerUrlValidator.validate]. It runs against the typed URL
 * with `BuildConfig.DEBUG && settings.allowHttpInDebug`, and [onServerUrlSaved] receives the trimmed
 * URL only when validation passes. Both debug switches exist only in debug builds.
 */
@Composable
fun ServerSettingsScreen(
    settings: AppSettings,
    onServerUrlSaved: (String) -> Unit,
    onAllowHttpInDebugChanged: (Boolean) -> Unit,
    onTrustSelfSignedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    introductoryContent: (@Composable () -> Unit)? = null,
    pinnedSpkiSha256: String? = null,
    onForgetServerKey: () -> Unit = {},
    rememberedDeviceExpiresAt: Long? = null,
    onForgetRememberedDevice: () -> Unit = {},
) {
    var serverUrl by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var validationMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    var savedMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    val validationMessage = validationMessageId?.let { UiText.Resource(it) }
    val savedMessage = savedMessageId?.let { UiText.Resource(it) }
    val spacing = MeshTheme.spacing

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Readable line length on tablets and resizable windows; compact phones keep 24 dp gutters.
        val horizontalPadding = if (maxWidth >= 600.dp) spacing.xxl else spacing.xl
        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding)
                    .padding(top = spacing.lg, bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                introductoryContent?.invoke()
                ServerIdentity()

                EndpointGroup(
                    serverUrl = serverUrl,
                    validationMessage = validationMessage,
                    onUrlChange = {
                        serverUrl = it
                        validationMessageId = null
                        savedMessageId = null
                    },
                )

                Button(
                    onClick = {
                        val error = ServerUrlValidator.validate(
                            serverUrl,
                            BuildConfig.DEBUG && settings.allowHttpInDebug,
                        )
                        validationMessageId = (error as? UiText.Resource)?.id
                        savedMessageId = null
                        if (error == null) {
                            onServerUrlSaved(serverUrl.trim())
                            savedMessageId = R.string.server_saved
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = PillShape,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        text = stringResource(R.string.server_save),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                savedMessage?.let { confirmation ->
                    SavedFeedback(message = confirmation.asString())
                }

                if (pinnedSpkiSha256 != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text(stringResource(R.string.tls_pinned_key), style = MaterialTheme.typography.titleSmall)
                        ServerKeyFingerprint(pinnedSpkiSha256)
                        TextButton(onClick = onForgetServerKey, shape = PillShape) { Text(stringResource(R.string.tls_forget)) }
                    }
                }

                if (introductoryContent == null) {
                    RememberedDeviceStatus(rememberedDeviceExpiresAt, onForgetRememberedDevice)
                }

                if (BuildConfig.DEBUG) {
                    DebugOptions(
                        allowHttpInDebug = settings.allowHttpInDebug,
                        trustSelfSignedCertificates = settings.trustSelfSignedCertificates,
                        onAllowHttpInDebugChanged = onAllowHttpInDebugChanged,
                        onTrustSelfSignedChanged = onTrustSelfSignedChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun RememberedDeviceStatus(expiresAt: Long?, onForget: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var active by remember(expiresAt) { mutableStateOf(expiresAt != null && expiresAt > System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        if (expiresAt != null && active) {
            kotlinx.coroutines.delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            active = false
        }
    }
    Column(modifier = Modifier.testTag("remember-device-status"),
        verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        Text(
            if (active && expiresAt != null) stringResource(R.string.remember_device_until,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale)
                    .format(java.util.Date(expiresAt)))
            else stringResource(R.string.remember_device_not_remembered),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (active) TextButton(onClick = onForget, shape = PillShape, modifier = Modifier.testTag("forget-remembered-device")) {
            Text(stringResource(R.string.remember_device_forget))
        }
    }
}

@Composable
private fun ServerIdentity() {
    val spacing = MeshTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        IconBadge(
            icon = Icons.Outlined.Dns,
            size = 44.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.server_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.server_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EndpointGroup(
    serverUrl: String,
    validationMessage: UiText?,
    onUrlChange: (String) -> Unit,
) {
    val spacing = MeshTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            title = stringResource(R.string.server_endpoint_section),
            subtitle = stringResource(R.string.server_endpoint_subtitle),
        )
        FlatGroup {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverUrl,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.server_url_label)) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.server_url_placeholder),
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    isError = validationMessage != null,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text(
                            text = validationMessage?.asString()
                                ?: stringResource(R.string.server_url_supporting),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    },
                )

                HttpsNote()
            }
        }
    }
}

/** The transport rule stays next to the field it applies to, as a quiet inline note. */
@Composable
private fun HttpsNote() {
    val spacing = MeshTheme.spacing
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = spacing.xs)
                .size(18.dp),
        )
        Text(
            text = stringResource(R.string.server_https_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavedFeedback(message: String) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DebugOptions(
    allowHttpInDebug: Boolean,
    trustSelfSignedCertificates: Boolean,
    onAllowHttpInDebugChanged: (Boolean) -> Unit,
    onTrustSelfSignedChanged: (Boolean) -> Unit,
) {
    val spacing = MeshTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SecondarySectionHeader(
            title = stringResource(R.string.server_debug_section),
            subtitle = stringResource(R.string.server_debug_subtitle),
        )
        FlatGroup {
            Column {
                ServerSwitchRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.server_allow_http),
                    supporting = stringResource(R.string.server_allow_http_supporting),
                    checked = allowHttpInDebug,
                    onCheckedChange = onAllowHttpInDebugChanged,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = spacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                ServerSwitchRow(
                    icon = Icons.Outlined.VerifiedUser,
                    title = stringResource(R.string.server_trust_self_signed),
                    supporting = stringResource(R.string.server_trust_self_signed_supporting),
                    checked = trustSelfSignedCertificates,
                    onCheckedChange = onTrustSelfSignedChanged,
                )
            }
        }
    }
}

/** Flat tonal grouping used by the form; no nested bordered cards. */
@Composable
private fun FlatGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/** Quiet label for the debug-only section, deliberately weaker than the primary form header. */
@Composable
private fun SecondarySectionHeader(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Secondary switch row for debug-only transport options. The whole row toggles. */
@Composable
private fun ServerSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
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
