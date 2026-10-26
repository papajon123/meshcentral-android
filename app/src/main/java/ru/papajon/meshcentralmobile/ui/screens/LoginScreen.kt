package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshAuthDiagnostics
import ru.papajon.meshcentralmobile.mesh.MeshAuthFailure
import ru.papajon.meshcentralmobile.mesh.TwoFactorChallenge
import ru.papajon.meshcentralmobile.mesh.TwoFactorChannel
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/**
 * Sign-in flow. The control channel authenticates with the `x-meshauth` header, so the form maps
 * directly onto it and two-factor answers reuse the same call with the code appended.
 */
@Composable
fun LoginScreen(
    meshClient: MeshCentralClient,
    secureVault: SecureVault,
    serverUrl: String,
    modifier: Modifier = Modifier,
    onContinueWithoutSignIn: (() -> Unit)? = null,
    onEditServer: () -> Unit = {},
    confirmingCertificateReplacement: Boolean = false,
    onTrustCertificate: (ServerCertificateFailure) -> Unit = {},
    onReplaceCertificate: (ServerCertificateFailure) -> Unit = {},
    onDismissCertificate: () -> Unit = meshClient::dismissCertificateFailure,
    onEditServerSettings: () -> Unit = onEditServer,
) {
    val storedCredentials = remember(serverUrl) { secureVault.getCredentials(serverUrl) }
    val clientState by meshClient.clientState.collectAsState()
    LoginContent(
        clientState = clientState, serverUrl = serverUrl, storedUsername = storedCredentials?.username,
        onLogin = { user, password, token -> meshClient.login(serverUrl, user, password, token) },
        onSubmitCode = { code, rememberDevice -> meshClient.submitTwoFactorCode(code, rememberDevice) },
        onStoredLogin = { meshClient.loginWithStoredCredentials(serverUrl) },
        onRequestCode = meshClient::requestTwoFactorCode,
        onContinueWithoutSignIn = onContinueWithoutSignIn,
        onEditServer = onEditServer, modifier = modifier,
        onEditServerSettings = onEditServerSettings,
    )
    clientState.certificateFailure?.let { failure ->
        ServerCertificateDialog(
            failure = failure,
            confirmingReplacement = confirmingCertificateReplacement,
            onTrust = { onTrustCertificate(failure) },
            onRequestReplacement = { onReplaceCertificate(failure) },
            onDismiss = onDismissCertificate,
        )
    }
}

@Composable
internal fun LoginContent(
    clientState: MeshClientState,
    serverUrl: String,
    storedUsername: String? = null,
    onLogin: (String, String, String?) -> Unit = { _, _, _ -> },
    onSubmitCode: (String, Boolean) -> Unit = { _, _ -> },
    onStoredLogin: () -> Unit = {},
    onRequestCode: (TwoFactorChannel) -> Unit = {},
    onContinueWithoutSignIn: (() -> Unit)? = null,
    onEditServer: () -> Unit = {},
    modifier: Modifier = Modifier,
    onEditServerSettings: () -> Unit = onEditServer,
) {
    // An edited endpoint must never inherit the previous server's in-progress credentials.
    var username by rememberSaveable(serverUrl) { mutableStateOf(storedUsername.orEmpty()) }
    var password by remember(serverUrl) { mutableStateOf("") }
    var token by remember(serverUrl) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(serverUrl) { mutableStateOf(false) }
    var rememberDevice by rememberSaveable(serverUrl, storedUsername) { mutableStateOf(true) }

    val connecting = clientState.connectionState in setOf(ConnectionState.Connecting, ConnectionState.Reconnecting)
    val challenge = clientState.twoFactorChallenge
    val error = clientState.lastError
    val connectionMessage = clientState.message
    val spacing = MeshTheme.spacing

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // One readable column: endpoint identity, one credential/2FA group, one primary action.
        val horizontalPadding = if (maxWidth >= 600.dp) spacing.xxl else spacing.xl
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding)
                .padding(top = spacing.xl, bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                IconBadge(
                    icon = Icons.Outlined.Lock,
                    size = 52.dp,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            ServerIdentityCard(serverUrl = serverUrl, onEditServer = onEditServer)

            // A single error surface: no second decorative pill repeating the same state.
            if (connecting && connectionMessage != null) {
                Text(text = connectionMessage.asString(), style = MaterialTheme.typography.bodyMedium)
            } else if (error != null) {
                LoginErrorCard(message = error.asString())
            } else if (clientState.connectionState == ConnectionState.Failed) {
                LoginErrorCard(message = stringResource(R.string.login_status_failed))
            }

            if (error == MeshAuthDiagnostics.describe(MeshAuthFailure.AccessKeyRequired)) {
                TextButton(onClick = onEditServerSettings, modifier = Modifier.testTag("login-server-settings")) {
                    Text(stringResource(R.string.login_server_settings))
                }
            }

            if (challenge == null) {
                CredentialsGroup(
                    username = username,
                    password = password,
                    passwordVisible = passwordVisible,
                    enabled = !connecting,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                )
            } else {
                TwoFactorGroup(
                    token = token,
                    challenge = challenge,
                    onTokenChange = { token = it },
                    onRequestCode = onRequestCode,
                    rememberDevice = rememberDevice,
                    onRememberDeviceChange = { rememberDevice = it },
                )
            }

            Button(
                onClick = {
                    if (challenge != null) onSubmitCode(token, rememberDevice && challenge.twoFactorCookieDays > 0)
                    else onLogin(username, password, null)
                    password = ""
                    token = ""
                },
                enabled = !connecting && if (challenge != null) {
                    token.isNotBlank()
                } else {
                    username.isNotBlank() && password.isNotBlank()
                },
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                if (connecting) {
                    MeshLoadingIndicator(size = 22.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.size(spacing.sm))
                    Text(stringResource(R.string.login_action_signing_in))
                } else {
                    Text(
                        if (challenge != null) {
                            stringResource(R.string.login_action_sign_in_with_code)
                        } else {
                            stringResource(R.string.login_action_sign_in)
                        },
                    )
                }
            }

            if (storedUsername != null && challenge == null) {
                TextButton(
                    onClick = onStoredLogin,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.login_saved_credentials, storedUsername))
                }
            }

            if (onContinueWithoutSignIn != null) {
                TextButton(
                    onClick = onContinueWithoutSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.login_continue_without))
                }
            }

            KeystoreNote()
        }
    }
}

/** Endpoint identity: the server the credentials belong to, with the one way to change it. */
@Composable
private fun ServerIdentityCard(serverUrl: String, onEditServer: () -> Unit) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(
                start = spacing.lg,
                top = spacing.md,
                end = spacing.sm,
                bottom = spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            IconBadge(
                icon = Icons.Outlined.Dns,
                size = 40.dp,
                container = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.settings_server_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onEditServer) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.login_edit_server),
                )
            }
        }
    }
}

/** One error, one recovery path: the endpoint control above and the primary action below. */
@Composable
private fun LoginErrorCard(message: String) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .padding(top = spacing.xs)
                    .size(20.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CredentialsGroup(
    username: String,
    password: String,
    passwordVisible: Boolean,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    var tokenHelpVisible by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = username,
                onValueChange = onUsernameChange,
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text(stringResource(R.string.login_username)) },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = onPasswordChange,
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text(stringResource(R.string.login_password)) },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisible, enabled = enabled) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (passwordVisible) {
                                stringResource(R.string.login_password_hide)
                            } else {
                                stringResource(R.string.login_password_show)
                            },
                        )
                    }
                },
            )
            TextButton(
                onClick = { tokenHelpVisible = !tokenHelpVisible },
                modifier = Modifier.testTag("login-token-help"),
            ) { Text(stringResource(R.string.token_login_help_title)) }
            if (tokenHelpVisible) {
                Text(
                    stringResource(R.string.token_login_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("login-token-instructions"),
                )
            }
        }
    }
}

/**
 * Verification step. Credentials are replaced rather than shown disabled: the code and its
 * available delivery channels are the only things the user needs before the same primary action.
 */
@Composable
private fun TwoFactorGroup(
    token: String,
    challenge: TwoFactorChallenge,
    onTokenChange: (String) -> Unit,
    onRequestCode: (TwoFactorChannel) -> Unit,
    rememberDevice: Boolean,
    onRememberDeviceChange: (Boolean) -> Unit,
) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                IconBadge(
                    icon = Icons.Outlined.VerifiedUser,
                    size = 40.dp,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.login_two_factor_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = token,
                onValueChange = onTokenChange,
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                label = { Text(stringResource(R.string.login_two_factor_code)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = PasswordVisualTransformation(),
            )

            if (challenge.twoFactorCookieDays > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("remember-device-checkbox")
                        .toggleable(value = rememberDevice, role = Role.Checkbox, onValueChange = onRememberDeviceChange),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Checkbox(checked = rememberDevice, onCheckedChange = null)
                    Text(pluralStringResource(R.plurals.remember_device_for_days, challenge.twoFactorCookieDays, challenge.twoFactorCookieDays),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }

            if (challenge.email || challenge.sms || challenge.push) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (challenge.email) {
                        ChannelButton(
                            icon = Icons.Outlined.MailOutline,
                            label = stringResource(R.string.login_two_factor_email),
                            onClick = { onRequestCode(TwoFactorChannel.Email) },
                        )
                    }
                    if (challenge.sms) {
                        ChannelButton(
                            icon = Icons.Outlined.Sms,
                            label = stringResource(R.string.login_two_factor_sms),
                            onClick = { onRequestCode(TwoFactorChannel.Sms) },
                        )
                    }
                    if (challenge.push) {
                        ChannelButton(
                            icon = Icons.Outlined.NotificationsActive,
                            label = stringResource(R.string.login_two_factor_push),
                            onClick = { onRequestCode(TwoFactorChannel.Push) },
                        )
                    }
                }
            }

            // This sentence refers to the code field above, so it stays after the field and actions.
            if (challenge.emailSent || challenge.smsSent || challenge.pushSent) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.login_two_factor_sent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val spacing = MeshTheme.spacing
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(spacing.sm))
        Text(label)
    }
}

@Composable
private fun KeystoreNote() {
    val spacing = MeshTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = spacing.xs)
                .size(16.dp),
        )
        Text(
            text = stringResource(R.string.login_keystore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
