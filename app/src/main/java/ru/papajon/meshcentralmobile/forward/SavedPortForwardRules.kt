package ru.papajon.meshcentralmobile.forward

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.theme.MeshMono
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/** Stable test tags for the saved-rule management surface. */
object SavedPortForwardRulesTags {
    const val Section = "saved_port_forward_section"
    const val Manage = "saved_port_forward_manage"
    const val Dialog = "saved_port_forward_dialog"
    const val Empty = "saved_port_forward_empty"
    const val Error = "saved_port_forward_error"
    const val Close = "saved_port_forward_close"

    fun rule(id: String) = "saved_port_forward_rule_$id"
    fun delete(id: String) = "saved_port_forward_delete_$id"
    fun status(id: String) = "saved_port_forward_status_$id"
}

/**
 * Settings section that lists every rule saved for [profileId], including rules whose device is
 * absent from the current [devices] snapshot or offline. It only manages persisted configuration:
 * it never starts a tunnel and never changes another profile's rules.
 */
@Composable
fun SavedPortForwardRules(
    profileId: String,
    store: PortForwardStore,
    manager: PortForwardManager,
    devices: List<MeshDevice>,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    val states by manager.states.collectAsState()
    var dialogOpen by rememberSaveable(profileId) { mutableStateOf(false) }
    var rules by remember(profileId, store) { mutableStateOf(store.rules(profileId)) }
    var deleteFailed by remember(profileId) { mutableStateOf(false) }
    val deviceNames = remember(devices) { devices.associate { it.nodeId to it.name } }
    val runningIds = remember(states, profileId) {
        states.asSequence()
            .filter { it.running && it.rule.profileId == profileId }
            .map { it.rule.id }
            .toHashSet()
    }

    Column(
        modifier = modifier.testTag(SavedPortForwardRulesTags.Section),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        SectionHeader(
            title = stringResource(R.string.pf_saved_title),
            subtitle = stringResource(R.string.pf_saved_count, rules.size),
            trailing = {
                TextButton(
                    onClick = {
                        rules = store.rules(profileId)
                        deleteFailed = false
                        dialogOpen = true
                    },
                    modifier = Modifier
                        .padding(start = spacing.sm)
                        .heightIn(min = 48.dp)
                        .testTag(SavedPortForwardRulesTags.Manage),
                ) {
                    Text(
                        text = stringResource(R.string.pf_saved_manage),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
        )
        MeshCard(shape = MaterialTheme.shapes.large) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                IconBadge(icon = Icons.Outlined.SwapHoriz)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pf_saved_available),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.pf_saved_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            modifier = Modifier.testTag(SavedPortForwardRulesTags.Dialog),
            title = { Text(stringResource(R.string.pf_saved_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (deleteFailed) {
                        Text(
                            text = stringResource(R.string.pf_saved_delete_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(SavedPortForwardRulesTags.Error),
                        )
                    }
                    if (rules.isEmpty()) {
                        Text(
                            text = stringResource(R.string.pf_saved_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(SavedPortForwardRulesTags.Empty),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            items(rules, key = { it.id }) { rule ->
                                SavedRuleRow(
                                    rule = rule,
                                    deviceName = deviceNames[rule.nodeId],
                                    active = rule.id in runningIds,
                                    onDelete = {
                                        if (manager.states.value.none { it.running && it.rule.id == rule.id } && store.delete(rule.id)) {
                                            deleteFailed = false
                                            rules = store.rules(profileId)
                                        } else {
                                            deleteFailed = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { dialogOpen = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(SavedPortForwardRulesTags.Close),
                ) {
                    Text(stringResource(R.string.pf_saved_close))
                }
            },
        )
    }
}

@Composable
private fun SavedRuleRow(
    rule: PortForwardRule,
    deviceName: String?,
    active: Boolean,
    onDelete: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SavedPortForwardRulesTags.rule(rule.id)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (deviceName != null) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.pf_saved_device_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = rule.nodeId,
                        style = MeshMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.pf_saved_endpoint,
                        stringResource(rule.kind.savedLabelRes()),
                        rule.remoteAddress.ifBlank { "127.0.0.1" },
                        rule.remotePort,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Text(
                        text = stringResource(R.string.pf_saved_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag(SavedPortForwardRulesTags.status(rule.id)),
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                enabled = !active,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(SavedPortForwardRulesTags.delete(rule.id)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.pf_saved_delete, rule.name),
                )
            }
        }
    }
}

@StringRes
private fun ForwardKind.savedLabelRes(): Int = when (this) {
    ForwardKind.RDP -> R.string.pf_kind_rdp
    ForwardKind.VNC -> R.string.pf_kind_vnc
    ForwardKind.SSH -> R.string.pf_kind_ssh
    ForwardKind.HTTP -> R.string.pf_kind_http
    ForwardKind.HTTP_ALT -> R.string.pf_kind_http_alt
    ForwardKind.HTTPS -> R.string.pf_kind_https
    ForwardKind.CUSTOM -> R.string.pf_kind_custom
}
