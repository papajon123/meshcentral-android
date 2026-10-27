package ru.papajon.meshcentralmobile.forward

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.R

/** Stable test tags for the production port-forwarding surface. */
object PortForwardTestTags {
    const val Screen = "port_forward_screen"
    const val Back = "port_forward_back"
    const val Add = "port_forward_add"
    const val StopAll = "port_forward_stop_all"
    const val Empty = "port_forward_empty"
    const val Warning = "port_forward_warning"
    const val Dialog = "port_forward_dialog"
    const val TemplateMenu = "port_forward_template_menu"
    const val Name = "port_forward_name"
    const val RemoteAddress = "port_forward_remote_address"
    const val RemotePort = "port_forward_remote_port"
    const val LocalPort = "port_forward_local_port"
    const val FormError = "port_forward_form_error"
    const val Save = "port_forward_save"
    const val Cancel = "port_forward_cancel"

    fun template(id: String) = "port_forward_template_$id"
    fun rule(id: String) = "port_forward_rule_$id"
    fun toggle(id: String) = "port_forward_toggle_$id"
    fun delete(id: String) = "port_forward_delete_$id"
    fun state(id: String) = "port_forward_state_$id"
    fun address(id: String) = "port_forward_address_$id"
    fun copy(id: String) = "port_forward_copy_$id"
    fun open(id: String) = "port_forward_open_$id"
    fun error(id: String) = "port_forward_error_$id"
}

/**
 * TCP forwarding rules for one device.
 *
 * [manager] owns live sockets and counters; [store] persists configuration only. The screen never
 * starts a rule on its own: turning a switch on calls [onStart], turning it off calls
 * [PortForwardManager.stop].
 */
@Composable
public fun PortForwardScreen(
    profileId: String,
    nodeId: String,
    manager: PortForwardManager,
    store: PortForwardStore,
    onStart: (PortForwardRule) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val states by manager.states.collectAsState()
    val statesById = remember(states) { states.associateBy { it.rule.id } }

    var rules by remember(profileId, nodeId, store) {
        mutableStateOf(store.rules(profileId, nodeId))
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    // Keep the last failure visible if the manager drops the state of a stopped rule.
    val previousErrors = remember { mutableStateMapOf<String, ForwardError>() }
    LaunchedEffect(states) {
        states.forEach { state ->
            if (state.running) previousErrors.remove(state.rule.id)
            else state.error?.let { previousErrors[state.rule.id] = it }
        }
    }

    fun notify(@StringRes message: Int) {
        scope.launch { snackbar.showSnackbar(context.getString(message)) }
    }

    fun openRule(rule: PortForwardRule, port: Int) {
        val uri = rule.kind.uri(port) ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            notify(R.string.pf_no_client)
        } catch (_: SecurityException) {
            notify(R.string.pf_no_client)
        }
    }

    val currentIds = remember(rules) { rules.mapTo(HashSet<String>()) { it.id } }
    val anyRunning = states.any { it.running && it.rule.id in currentIds }

    Box(Modifier.fillMaxSize().testTag(PortForwardTestTags.Screen)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag(PortForwardTestTags.Back),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.pf_back),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.pf_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.pf_channel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
                TextButton(
                    onClick = { manager.stopDevice(profileId, nodeId) },
                    enabled = anyRunning,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 20.dp)
                        .heightIn(min = 48.dp)
                        .testTag(PortForwardTestTags.StopAll),
                ) {
                    Text(stringResource(R.string.pf_stop_device))
                }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "add") {
                    Button(
                        onClick = { adding = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(PortForwardTestTags.Add),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pf_add))
                    }
                }
                item(key = "warning") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.Warning),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.pf_security_warning, PortForwardManager.IDLE_TIMEOUT_MINUTES),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (rules.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                                .testTag(PortForwardTestTags.Empty),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.pf_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(rules, key = { it.id }) { rule ->
                        val state = statesById[rule.id]
                        RuleCard(
                            rule = rule,
                            state = state,
                            retainedError = previousErrors[rule.id],
                            onToggle = { enabled ->
                                previousErrors.remove(rule.id)
                                if (enabled) onStart(rule) else manager.stop(rule.id)
                            },
                            onDelete = {
                                if (store.delete(rule.id)) {
                                    rules = store.rules(profileId, nodeId)
                                } else {
                                    notify(R.string.pf_delete_failed)
                                }
                            },
                            onCopy = { port ->
                                clipboard.setText(AnnotatedString("127.0.0.1:$port"))
                                notify(R.string.pf_copied)
                            },
                            onOpen = { port -> openRule(rule, port) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (adding) {
        AddRuleDialog(
            profileId = profileId,
            nodeId = nodeId,
            onDismiss = { adding = false },
            onSave = { candidate ->
                store.save(candidate).also { saved ->
                    if (saved) rules = store.rules(profileId, nodeId)
                }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: PortForwardRule,
    state: PortForwardState?,
    retainedError: ForwardError?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onCopy: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val running = state?.running == true
    val error = state?.error ?: if (!running) retainedError else null
    val toggleLabel = stringResource(R.string.pf_toggle, rule.name)
    val deleteLabel = stringResource(R.string.pf_delete_rule, rule.name)
    val targetHost = rule.remoteAddress.ifBlank { "127.0.0.1" }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.rule(rule.id)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${stringResource(rule.kind.labelRes())} · $targetHost:${rule.remotePort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .testTag(PortForwardTestTags.toggle(rule.id))
                        .semantics { contentDescription = toggleLabel },
                )
                IconButton(
                    onClick = onDelete,
                    enabled = !running,
                    modifier = Modifier.size(48.dp).testTag(PortForwardTestTags.delete(rule.id)),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = deleteLabel)
                }
            }

            if (state != null && state.running) {
                if (state.connections > 0) Text(stringResource(R.string.pf_target_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(
                        if (state.localPort > 0) R.string.pf_status_running else R.string.pf_starting,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(PortForwardTestTags.state(rule.id)),
                )
                if (state.localPort > 0) {
                    LocalAddress(
                        rule = rule,
                        port = state.localPort,
                        onCopy = { onCopy(state.localPort) },
                        onOpen = { onOpen(state.localPort) },
                    )
                }
                Text(
                    text = stringResource(R.string.pf_connections, state.connections),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.pf_bytes,
                        Formatter.formatShortFileSize(LocalContext.current, state.bytesSent),
                        Formatter.formatShortFileSize(LocalContext.current, state.bytesReceived),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                        text = stringResource(if (state?.autoStopped == true) R.string.pf_idle_stopped else R.string.pf_status_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(PortForwardTestTags.state(rule.id)),
                )
            }

            if (error != null) {
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(PortForwardTestTags.error(rule.id)),
                )
            }
        }
    }
}

@Composable
private fun LocalAddress(
    rule: PortForwardRule,
    port: Int,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.pf_local_address),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "127.0.0.1:$port",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(PortForwardTestTags.address(rule.id)),
                    )
                }
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(48.dp).testTag(PortForwardTestTags.copy(rule.id)),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.pf_copy_address),
                    )
                }
            }
            if (rule.kind != ForwardKind.CUSTOM) {
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(PortForwardTestTags.open(rule.id)),
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pf_open))
                }
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    profileId: String,
    nodeId: String,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Boolean,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var name by rememberSaveable(key = "pf_form_name") { mutableStateOf("") }
    var remoteAddress by rememberSaveable(key = "pf_form_remote_address") { mutableStateOf("") }
    var remotePort by rememberSaveable(key = "pf_form_remote_port") {
        mutableStateOf(TEMPLATES.first().port.toString())
    }
    var localPort by rememberSaveable(key = "pf_form_local_port") { mutableStateOf("") }
    var kindName by rememberSaveable(key = "pf_form_kind") {
        mutableStateOf(TEMPLATES.first().kind.name)
    }
    var templateId by rememberSaveable(key = "pf_form_template") {
        mutableStateOf(TEMPLATES.first().id)
    }
    var templateMenu by remember { mutableStateOf(false) }
    var error by rememberSaveable(key = "pf_form_error") { mutableStateOf<Int?>(null) }

    val kind = ForwardKind.entries.firstOrNull { it.name == kindName } ?: ForwardKind.CUSTOM
    val selectedTemplate = TEMPLATES.firstOrNull { it.id == templateId } ?: TEMPLATES.first()

    fun applyTemplate(template: Template) {
        templateId = template.id
        kindName = template.kind.name
        remotePort = if (template.kind == ForwardKind.CUSTOM) "" else template.port.toString()
        if (name.isBlank()) name = context.getString(template.label)
        templateMenu = false
        error = null
    }

    fun submit() {
        val trimmedName = name.trim()
        val trimmedAddress = remoteAddress.trim()
        val parsedRemotePort = remotePort.trim().toIntOrNull()
        val parsedLocalPort = if (localPort.isBlank()) 0 else localPort.trim().toIntOrNull()
        val problem = when {
            trimmedName.isEmpty() -> R.string.pf_form_name_required
            trimmedName.length > 80 || trimmedName.any { it.isISOControl() } -> R.string.pf_form_name_invalid
            parsedRemotePort == null || parsedRemotePort !in 1..65535 -> R.string.pf_form_remote_port_invalid
            parsedLocalPort == null || parsedLocalPort !in 0..65535 -> R.string.pf_form_local_port_invalid
            !PortForwardRule.validHost(trimmedAddress) -> R.string.pf_form_address_invalid
            else -> null
        }
        if (problem != null) {
            error = problem
            return
        }
        val rule = PortForwardRule(
            profileId = profileId,
            nodeId = nodeId,
            name = trimmedName,
            kind = kind,
            remotePort = parsedRemotePort!!,
            remoteAddress = trimmedAddress,
            localPort = parsedLocalPort!!,
        )
        if (!rule.valid()) {
            error = R.string.pf_form_invalid
            return
        }
        if (!onSave(rule)) {
            error = R.string.pf_form_save_failed
            return
        }
        focus.clearFocus()
        keyboard?.hide()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PortForwardTestTags.Dialog),
        title = { Text(stringResource(R.string.pf_add_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    OutlinedButton(
                        onClick = { templateMenu = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(PortForwardTestTags.TemplateMenu),
                    ) {
                        Text(
                            "${stringResource(R.string.pf_templates)}: " +
                                stringResource(selectedTemplate.label),
                        )
                    }
                    DropdownMenu(
                        expanded = templateMenu,
                        onDismissRequest = { templateMenu = false },
                    ) {
                        TEMPLATES.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(stringResource(template.label)) },
                                onClick = { applyTemplate(template) },
                                modifier = Modifier.testTag(PortForwardTestTags.template(template.id)),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { value ->
                        name = value.filterNot { it == '\n' || it == '\r' }.take(80)
                        error = null
                    },
                    label = { Text(stringResource(R.string.pf_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.Name),
                )
                OutlinedTextField(
                    value = remoteAddress,
                    onValueChange = { value ->
                        remoteAddress = value.filterNot { it == '\n' || it == '\r' }.take(253)
                        error = null
                    },
                    label = { Text(stringResource(R.string.pf_remote_address)) },
                    placeholder = { Text(stringResource(R.string.pf_remote_address_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.RemoteAddress),
                )
                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { value ->
                        remotePort = value.filterNot { it == '\n' || it == '\r' }.take(6)
                        error = null
                    },
                    label = { Text(stringResource(R.string.pf_remote_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.RemotePort),
                )
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { value ->
                        localPort = value.filterNot { it == '\n' || it == '\r' }.take(6)
                        error = null
                    },
                    label = { Text(stringResource(R.string.pf_local_port)) },
                    placeholder = { Text(stringResource(R.string.pf_local_port_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(PortForwardTestTags.LocalPort),
                )

                error?.let { message ->
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(PortForwardTestTags.FormError),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                modifier = Modifier.heightIn(min = 48.dp).testTag(PortForwardTestTags.Save),
            ) {
                Text(stringResource(R.string.pf_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag(PortForwardTestTags.Cancel),
            ) {
                Text(stringResource(R.string.pf_cancel))
            }
        },
    )
}

@StringRes
private fun ForwardKind.labelRes(): Int = when (this) {
    ForwardKind.RDP -> R.string.pf_kind_rdp
    ForwardKind.VNC -> R.string.pf_kind_vnc
    ForwardKind.SSH -> R.string.pf_kind_ssh
    ForwardKind.HTTP -> R.string.pf_kind_http
    ForwardKind.HTTP_ALT -> R.string.pf_kind_http_alt
    ForwardKind.HTTPS -> R.string.pf_kind_https
    ForwardKind.CUSTOM -> R.string.pf_kind_custom
}

@StringRes
private fun ForwardError.messageRes(): Int = when (this) {
    ForwardError.INVALID_RULE -> R.string.pf_error_invalid_rule
    ForwardError.UNAVAILABLE -> R.string.pf_error_unavailable
    ForwardError.PORT_IN_USE -> R.string.pf_error_port_in_use
    ForwardError.LIMIT -> R.string.pf_error_limit
    ForwardError.CONNECT_FAILED -> R.string.pf_error_connect_failed
    ForwardError.TIMEOUT -> R.string.pf_error_timeout
    ForwardError.CONNECTION_LOST -> R.string.pf_error_connection_lost
    ForwardError.TLS -> R.string.pf_error_tls
    ForwardError.SERVICE -> R.string.pf_error_service
}

private data class Template(
    val id: String,
    val kind: ForwardKind,
    @StringRes val label: Int,
    val port: Int,
)

private val TEMPLATES = listOf(
    Template("rdp", ForwardKind.RDP, R.string.pf_template_rdp, ForwardKind.RDP.defaultPort),
    Template("vnc", ForwardKind.VNC, R.string.pf_template_vnc, ForwardKind.VNC.defaultPort),
    Template("ssh", ForwardKind.SSH, R.string.pf_template_ssh, ForwardKind.SSH.defaultPort),
    Template("http80", ForwardKind.HTTP, R.string.pf_template_http80, 80),
    Template("http8080", ForwardKind.HTTP_ALT, R.string.pf_template_http8080, 8080),
    Template("https", ForwardKind.HTTPS, R.string.pf_template_https, ForwardKind.HTTPS.defaultPort),
    Template("custom", ForwardKind.CUSTOM, R.string.pf_template_custom, 0),
)
