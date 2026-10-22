package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.StatusDot
import ru.papajon.meshcentralmobile.ui.components.desktopCapabilityReason
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/**
 * Device workspace: a compact identity header, the six existing tool tabs, and a structured
 * overview in which every tool keeps its availability reason visible.
 */
@Composable
fun DeviceScreen(
    device: MeshDevice,
    server: MeshServerCapabilities,
    current: Boolean,
    selected: DeviceTab,
    onSelect: (DeviceTab) -> Unit,
    onBack: () -> Unit,
    onWebPanel: () -> Unit,
    modifier: Modifier = Modifier,
    sessionContent: @Composable () -> Unit,
) {
    if (selected == DeviceTab.System || selected == DeviceTab.Help) {
        sessionContent()
        return
    }
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    fun available(tab: DeviceTab): Boolean = when (tab) {
        DeviceTab.Overview -> true
        DeviceTab.System, DeviceTab.Help -> current
        DeviceTab.Desktop -> current && device.canOpenNativeDesktop
        else -> current && device.sessionAvailability(tab.protocol!!, server).canAttempt
    }
    val statusColor = when {
        !current -> MaterialTheme.colorScheme.outline
        device.online -> MeshTheme.colors.online
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = stringResource(when {
        !current -> R.string.connection_status_unknown
        device.online -> R.string.common_status_online
        else -> R.string.common_status_offline
    })

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.device_back),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(color = statusColor, size = 8.dp)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { toolsMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.ExpandMore, contentDescription = stringResource(R.string.workspace_tools))
                }
                DropdownMenu(expanded = toolsMenuExpanded, onDismissRequest = { toolsMenuExpanded = false }) {
                    DeviceTab.entries.forEach { tab ->
                        DropdownMenuItem(
                            text = { Text(stringResource(tab.labelRes)) },
                            leadingIcon = { Icon(tab.icon(), contentDescription = null) },
                            enabled = available(tab),
                            onClick = { toolsMenuExpanded = false; onSelect(tab) },
                        )
                    }
                }
            }
        }
        ScrollableTabRow(
            modifier = Modifier.fillMaxWidth(),
            selectedTabIndex = selected.ordinal,
            edgePadding = 4.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DeviceTab.entries.forEach { tab ->
                Tab(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    enabled = available(tab),
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        if (selected == DeviceTab.Overview) {
            DeviceOverview(
                device = device,
                server = server,
                current = current,
                available = { tab -> available(tab) },
                onSelect = onSelect,
                onWebPanel = onWebPanel,
            )
        } else {
            sessionContent()
        }
    }
}

@Composable
private fun DeviceOverview(
    device: MeshDevice,
    server: MeshServerCapabilities,
    current: Boolean,
    available: (DeviceTab) -> Boolean,
    onSelect: (DeviceTab) -> Unit,
    onWebPanel: () -> Unit,
) {
    val windowWidth = LocalConfiguration.current.screenWidthDp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The navigation rail reduces the content width on tablets, so the window size decides the
        // grid and the measured width keeps at least two usable columns.
        val twoColumns = windowWidth >= 600 && maxWidth >= 420.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DeviceMetadata(device = device)
            Text(
                text = stringResource(R.string.workspace_tools),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!current) {
                Text(
                    text = stringResource(R.string.device_signin_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            val tools = DeviceTab.entries.filter { it != DeviceTab.Overview }
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (rowTabs in tools.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            for (tab in rowTabs) {
                                ToolChoice(
                                    tab = tab,
                                    enabled = available(tab),
                                    reason = toolReason(tab, device, server, current),
                                    onClick = { onSelect(tab) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowTabs.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    for ((index, tab) in tools.withIndex()) {
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ToolChoice(
                            tab = tab,
                            enabled = available(tab),
                            reason = toolReason(tab, device, server, current),
                            onClick = { onSelect(tab) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.workspace_session_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onWebPanel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.device_web_panel))
            }
            Text(
                text = stringResource(R.string.device_web_panel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceMetadata(device: MeshDevice) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val twoColumns = maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.4f
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DeviceValue(
                            label = R.string.device_group,
                            value = device.meshName,
                            modifier = Modifier.weight(1f),
                        )
                        DeviceValue(
                            label = R.string.device_os,
                            value = device.osDescription,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DeviceValue(
                            label = R.string.device_address,
                            value = device.host ?: device.ipAddress,
                            modifier = Modifier.weight(1f),
                            leftToRight = true,
                        )
                        DeviceValue(
                            label = R.string.device_agent_version,
                            value = device.agentVersion?.takeUnless { it == "0" }.orEmpty(),
                            modifier = Modifier.weight(1f),
                            leftToRight = true,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeviceValue(label = R.string.device_group, value = device.meshName)
                    DeviceValue(label = R.string.device_os, value = device.osDescription)
                    DeviceValue(
                        label = R.string.device_address,
                        value = device.host ?: device.ipAddress,
                        leftToRight = true,
                    )
                    DeviceValue(
                        label = R.string.device_agent_version,
                        value = device.agentVersion?.takeUnless { it == "0" }.orEmpty(),
                        leftToRight = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceValue(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    leftToRight: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val displayValue = if (value.isBlank()) stringResource(R.string.device_not_reported) else value
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = displayValue,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDirection = if (leftToRight) TextDirection.Ltr else TextDirection.Content,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ToolChoice(
    tab: DeviceTab,
    enabled: Boolean,
    reason: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(tab.labelRes),
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(
                icon = tab.icon(),
                size = 42.dp,
                container = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (enabled) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun toolReason(
    tab: DeviceTab,
    device: MeshDevice,
    server: MeshServerCapabilities,
    current: Boolean,
): String {
    if (!current) return stringResource(R.string.device_signin_needed)
    if (tab == DeviceTab.Desktop && !device.canOpenNativeDesktop) {
        return desktopCapabilityReason(device.desktopCapability)
    }
    val availability = tab.protocol?.let { device.sessionAvailability(it, server) }
    return availability?.label?.asString() ?: SessionAvailability.Available.label.asString()
}

private fun DeviceTab.icon(): ImageVector = when (this) {
    DeviceTab.Overview -> Icons.Outlined.DesktopWindows
    DeviceTab.Desktop -> Icons.Outlined.DesktopWindows
    DeviceTab.Terminal -> Icons.Outlined.Terminal
    DeviceTab.Files -> Icons.Outlined.Folder
    DeviceTab.Ssh -> Icons.Outlined.VpnKey
    DeviceTab.Sftp -> Icons.Outlined.SwapHoriz
    DeviceTab.System -> Icons.Outlined.Info
    DeviceTab.Help -> Icons.Outlined.SupportAgent
}
