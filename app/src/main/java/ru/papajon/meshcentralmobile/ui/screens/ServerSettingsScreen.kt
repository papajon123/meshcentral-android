package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/**
 * Server onboarding form: one labelled field, an inline HTTPS note, the debug-only switches and a
 * single prominent save action that reports both failure (error colour) and success.
 *
 * Validation is centralized in [ServerUrlValidator.validate]: it runs against the typed URL with
 * `BuildConfig.DEBUG && settings.allowHttpInDebug`, and [onServerUrlSaved] receives the trimmed URL
 * only when validation passes. The two debug switches exist only in debug builds.
 */
@Composable
fun ServerSettingsScreen(
    settings: AppSettings,
    onServerUrlSaved: (String) -> Unit,
    onAllowHttpInDebugChanged: (Boolean) -> Unit,
    onTrustSelfSignedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    introductoryContent: (@Composable () -> Unit)? = null,
) {
    var serverUrl by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var validationMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    var savedMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    val validationMessage = validationMessageId?.let { UiText.Resource(it) }
    val savedMessage = savedMessageId?.let { UiText.Resource(it) }

    val spacing = MeshTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        introductoryContent?.invoke()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBadge(
                icon = Icons.Outlined.Dns,
                size = 52.dp,
                container = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.server_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionHeader(
                title = stringResource(R.string.server_endpoint_section),
                subtitle = stringResource(R.string.server_endpoint_subtitle),
            )
            MeshCard(shape = MaterialTheme.shapes.large) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverUrl,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        onValueChange = {
                            serverUrl = it
                            validationMessageId = null
                            savedMessageId = null
                        },
                        label = { Text(stringResource(R.string.server_url_label)) },
                        placeholder = { Text(stringResource(R.string.server_url_placeholder)) },
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
                            )
                        },
                    )

                    HttpsNote()
                }
            }
        }

        if (BuildConfig.DEBUG) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                SectionHeader(
                    title = stringResource(R.string.server_debug_section),
                    subtitle = stringResource(R.string.server_debug_subtitle),
                )
                MeshCard(shape = MaterialTheme.shapes.large) {
                    Column {
                        ServerSwitchRow(
                            icon = Icons.Outlined.Language,
                            title = stringResource(R.string.server_allow_http),
                            supporting = stringResource(R.string.server_allow_http_supporting),
                            checked = settings.allowHttpInDebug,
                            onCheckedChange = onAllowHttpInDebugChanged,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        ServerSwitchRow(
                            icon = Icons.Outlined.VerifiedUser,
                            title = stringResource(R.string.server_trust_self_signed),
                            supporting = stringResource(R.string.server_trust_self_signed_supporting),
                            checked = settings.trustSelfSignedCertificates,
                            onCheckedChange = onTrustSelfSignedChanged,
                        )
                    }
                }
            }
        }

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
                .heightIn(min = 54.dp),
            shape = PillShape,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.server_save), style = MaterialTheme.typography.labelLarge)
        }

        validationMessage?.let { error ->
            FormFeedback(
                message = error.asString(),
                color = MaterialTheme.colorScheme.error,
                icon = Icons.Outlined.ErrorOutline,
            )
        }
        savedMessage?.let { confirmation ->
            FormFeedback(
                message = confirmation.asString(),
                color = MeshTheme.colors.online,
                icon = Icons.Outlined.CheckCircle,
            )
        }
    }
}

/** Locked-down explanation of the transport rule, right under the field it applies to. */
@Composable
private fun HttpsNote(modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Text(
            text = stringResource(R.string.server_https_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Icon plus text feedback line; the colour carries the outcome, the icon repeats it. */
@Composable
private fun FormFeedback(
    message: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = MeshTheme.motion.effectColor,
        label = "form-feedback-color",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = animatedColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = animatedColor,
        )
    }
}

/** Switch row for the debug-only transport options. The whole row toggles, not just the switch. */
@Composable
private fun ServerSwitchRow(
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
        label = "server-row-press",
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
        IconBadge(
            icon = icon,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
