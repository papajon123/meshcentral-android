package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.desktopCapabilityReason

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
    fun available(tab: DeviceTab): Boolean = when (tab) {
        DeviceTab.Overview -> true
        DeviceTab.Desktop -> current && device.canOpenNativeDesktop
        else -> current && device.sessionAvailability(tab.protocol!!, server).canAttempt
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.device_back))
            }
            Text(device.name, Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium)
        }
        ScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 4.dp) {
            DeviceTab.entries.forEach { tab ->
                Tab(selected = selected == tab, onClick = { onSelect(tab) }, enabled = available(tab),
                    text = { Text(stringResource(tab.labelRes)) })
            }
        }
        if (selected == DeviceTab.Overview) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceValue(R.string.device_group, device.meshName)
                DeviceValue(R.string.device_os, device.osDescription)
                DeviceValue(R.string.device_address, device.host ?: device.ipAddress, leftToRight = true)
                DeviceValue(R.string.device_agent_version, device.agentVersion.orEmpty(), leftToRight = true)
                Text(stringResource(R.string.device_actions), style = MaterialTheme.typography.titleMedium)
                if (!current) Text(stringResource(R.string.device_signin_needed))
                DeviceTab.entries.filter { it != DeviceTab.Overview }.forEach { tab ->
                    val availability = tab.protocol?.let { device.sessionAvailability(it, server) }
                    MeshCard(Modifier.fillMaxWidth(), onClick = if (available(tab)) ({ onSelect(tab) }) else null) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(tab.labelRes), style = MaterialTheme.typography.titleSmall)
                            Text(when {
                                !current -> stringResource(R.string.device_signin_needed)
                                tab == DeviceTab.Desktop && !device.canOpenNativeDesktop -> desktopCapabilityReason(device.desktopCapability)
                                availability != null -> availability.label.asString()
                                else -> SessionAvailability.Available.label.asString()
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.device_session_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onWebPanel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_web_panel))
                }
                Text(stringResource(R.string.device_web_panel_hint), style = MaterialTheme.typography.bodySmall)
            }
        } else sessionContent()
    }
}

@Composable
private fun DeviceValue(label: Int, value: String, leftToRight: Boolean = false) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { stringResource(R.string.device_not_reported) },
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = if (leftToRight) TextDirection.Ltr else TextDirection.Content))
    }
}
