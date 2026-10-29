package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.KvmPacketParser
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

@Composable
internal fun SessionTopBar(
    state: RemoteDesktopState,
    onBack: () -> Unit,
    onSelectDisplay: (Int) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onHideChrome: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    debugDetails: String? = null,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = MeshTheme.spacing.sm, vertical = MeshTheme.spacing.xs)
            .testTag("session-top-bar"),
        shape = MaterialTheme.shapes.large, color = MeshTheme.colors.glass, tonalElevation = 2.dp,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.session_action_end))
                }
                Column(Modifier.weight(1f)) {
                    Text(state.device?.name ?: stringResource(R.string.session_title_fallback),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sessionTrafficRate(state.bytesPerSecond),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("session-stream-rate"))
                }
                SessionConnectionStatus(state, onRefresh = onRefresh, onReconnect = onReconnect, extraDetails = debugDetails)
                IconButton(onClick = onHideChrome, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.ArrowDropUp, stringResource(R.string.session_action_hide_controls))
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = MeshTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                SessionDisplaySelector(state, onSelectDisplay, Modifier.weight(1f))
                if (onMinimize != null) IconButton(onClick = onMinimize, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Remove, stringResource(R.string.workspace_minimize))
                }
                SessionRefreshAction(state, onRefresh, onReconnect)
            }
        }
    }
}

@Composable
internal fun SessionDisplaySelector(state: RemoteDesktopState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val advertised = state.displays.distinct()
    val monitors = advertised.filter { it != KvmPacketParser.ALL_DISPLAYS_INDEX }
    if (monitors.isEmpty()) { Spacer(modifier); return }
    // All is the protocol's virtual-desktop selection, not an invented physical monitor ID.
    val choices = if (monitors.size > 1 && KvmPacketParser.ALL_DISPLAYS_INDEX !in advertised)
        advertised + KvmPacketParser.ALL_DISPLAYS_INDEX else advertised
    val canSelect = state.connectionState == ConnectionState.Connected && state.handshakeComplete
    val labels = choices.associateWith { displayLabel(it) }
    val selected = state.selectedDisplay ?: KvmPacketParser.ALL_DISPLAYS_INDEX
    if (monitors.size <= 2) {
        Row(modifier.horizontalScroll(rememberScrollState())) {
            // Place All last, even when the protocol lists its sentinel first.
            (monitors + choices.filter { it == KvmPacketParser.ALL_DISPLAYS_INDEX }).forEach { id ->
                IconButton(onClick = { onSelect(id) }, enabled = canSelect,
                    modifier = Modifier.size(48.dp).testTag("session-display-$id").semantics { this.selected = selected == id },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (selected == id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (selected == id) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )) { DisplayIcon(id) }
            }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier) {
            IconButton(onClick = { expanded = true }, enabled = canSelect, modifier = Modifier.size(48.dp).testTag("session-display-menu")) {
                Box {
                    DisplayIcon(selected)
                    Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(14.dp).align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surface))
                }
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { id ->
                    DropdownMenuItem(text = { Text(labels.getValue(id)) }, enabled = canSelect,
                        modifier = Modifier.testTag("session-display-choice-$id").semantics { this.selected = selected == id },
                        onClick = { expanded = false; onSelect(id) })
                }
            }
        }
    }
}

@Composable
private fun DisplayIcon(id: Int) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Icon(if (id == KvmPacketParser.ALL_DISPLAYS_INDEX) Icons.Outlined.SelectAll else Icons.Outlined.DesktopWindows,
            displayLabel(id), Modifier.size(28.dp))
        if (id != KvmPacketParser.ALL_DISPLAYS_INDEX) {
            Text(id.toString(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                modifier = Modifier.offset(y = (-3).dp))
        }
    }
}

@Composable private fun displayLabel(id: Int): String = if (id == KvmPacketParser.ALL_DISPLAYS_INDEX)
    stringResource(R.string.session_screen_all) else stringResource(R.string.session_screen_short, id)
