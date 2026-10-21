package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.TwoFactorChannel
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.ConnectionNotice
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.components.StatusPill
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
) {
    val storedCredentials = remember(serverUrl) { secureVault.getCredentials(serverUrl) }
    val clientState by meshClient.clientState.collectAsState()
    LoginContent(
        clientState = clientState, serverUrl = serverUrl, storedUsername = storedCredentials?.username,
        onLogin = { user, password, token -> meshClient.login(serverUrl, user, password, token) },
        onSubmitCode = meshClient::submitTwoFactorCode,
        onStoredLogin = { meshClient.loginWithStoredCredentials(serverUrl) },
        onRequestCode = meshClient::requestTwoFactorCode,
        onContinueWithoutSignIn = onContinueWithoutSignIn,
        onEditServer = onEditServer, modifier = modifier,
    )
}

@Composable
internal fun LoginContent(
    clientState: MeshClientState,
    serverUrl: String,
    storedUsername: String? = null,
    onLogin: (String, String, String?) -> Unit = { _, _, _ -> },
    onSubmitCode: (String) -> Unit = {},
    onStoredLogin: () -> Unit = {},
    onRequestCode: (TwoFactorChannel) -> Unit = {},
    onContinueWithoutSignIn: (() -> Unit)? = null,
    onEditServer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // An edited endpoint must never inherit the previous server's in-progress credentials.
    var username by rememberSaveable(serverUrl) { mutableStateOf(storedUsername.orEmpty()) }
    var password by remember(serverUrl) { mutableStateOf("") }
    var token by remember(serverUrl) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(serverUrl) { mutableStateOf(false) }

    val connecting = clientState.connectionState in setOf(ConnectionState.Connecting, ConnectionState.Reconnecting)
    val challenge = clientState.twoFactorChallenge
    val error = clientState.lastError

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(
                icon = Icons.Outlined.Lock,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                container = MaterialTheme.colorScheme.primaryContainer,
                size = 52.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEditServer) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.login_edit_server))
            }
        }

        if (error != null) {
            ConnectionNotice(message = error)
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            label = { Text(stringResource(R.string.login_username)) },
            enabled = challenge == null && !connecting,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            label = { Text(stringResource(R.string.login_password)) },
            enabled = challenge == null && !connecting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) {
                            stringResource(R.string.login_password_hide)
                        } else {
                            stringResource(R.string.login_password_show)
                        },
                    )
                }
            },
        )

        if (challenge != null) {
            val channels = buildList {
                if (challenge.email) add(stringResource(R.string.login_two_factor_channel_email))
                if (challenge.sms) add(stringResource(R.string.login_two_factor_channel_sms))
                if (challenge.push) add(stringResource(R.string.login_two_factor_channel_push))
            }
            val challengeTitle = stringResource(R.string.login_two_factor_title) +
                if (channels.isEmpty()) "" else " (" + channels.joinToString(", ") + ")"
            MeshCard(
                modifier = Modifier.fillMaxWidth(),
                container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(challengeTitle, style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = token,
                        onValueChange = { token = it },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        label = { Text(stringResource(R.string.login_two_factor_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (challenge.email) {
                            OutlinedButton(
                                onClick = { onRequestCode(TwoFactorChannel.Email) },
                                shape = PillShape,
                            ) {
                                Icon(Icons.Outlined.MailOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(stringResource(R.string.login_two_factor_email))
                            }
                        }
                        if (challenge.sms) {
                            OutlinedButton(
                                onClick = { onRequestCode(TwoFactorChannel.Sms) },
                                shape = PillShape,
                            ) {
                                Icon(Icons.Outlined.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(stringResource(R.string.login_two_factor_sms))
                            }
                        }
                        if (challenge.push) {
                            OutlinedButton(
                                onClick = { onRequestCode(TwoFactorChannel.Push) },
                                shape = PillShape,
                            ) {
                                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(stringResource(R.string.login_two_factor_push))
                            }
                        }
                    }
                    if (challenge.emailSent || challenge.smsSent || challenge.pushSent) {
                        Text(
                            text = stringResource(R.string.login_two_factor_sent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                if (challenge != null) onSubmitCode(token) else onLogin(username, password, null)
                password = ""
                token = ""
            },
            enabled = !connecting && if (challenge != null) token.isNotBlank() else username.isNotBlank() && password.isNotBlank(),
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (connecting) {
                MeshLoadingIndicator(size = 22.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.size(10.dp))
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            StatusPill(
                text = when (clientState.connectionState) {
                    ConnectionState.Connecting -> stringResource(R.string.login_status_connecting)
                    ConnectionState.Reconnecting -> stringResource(R.string.connection_reconnecting)
                    ConnectionState.Authenticated -> stringResource(R.string.login_status_authenticated)
                    ConnectionState.Failed -> stringResource(R.string.login_status_failed)
                    else -> stringResource(R.string.login_status_idle)
                },
                color = when (clientState.connectionState) {
                    ConnectionState.Authenticated -> MeshTheme.colors.online
                    ConnectionState.Failed -> MeshTheme.colors.danger
                    else -> MeshTheme.colors.offline
                },
            )
        }

        if (onContinueWithoutSignIn != null) {
            TextButton(
                onClick = onContinueWithoutSignIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.login_continue_without))
            }
        }

        Text(
            text = stringResource(R.string.login_keystore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp),
        )
    }
}
