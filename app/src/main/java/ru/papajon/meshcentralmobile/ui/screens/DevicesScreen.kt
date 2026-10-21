package ru.papajon.meshcentralmobile.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.ConnectionNotice
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.components.MeshScreenHeader
import ru.papajon.meshcentralmobile.ui.components.desktopCapabilityReason
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

private enum class DeviceFilter(@StringRes val labelRes: Int) {
    All(R.string.devices_filter_all),
    Online(R.string.devices_filter_online),
    Favorites(R.string.devices_filter_favorites),
}

/**
 * Computer list: the app's home. One card per computer with its transport state, the reason a
 * connection is unavailable, and a single primary action.
 */
@Composable
fun DevicesScreen(
    meshClient: MeshCentralClient,
    favorites: List<FavoriteLink>,
    onAddFavorite: (String, String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clientState by meshClient.clientState.collectAsState()
    DevicesContent(
        clientState, favorites, onAddFavorite, onRemoveFavorite,
        onConnect = { device -> meshClient.connectDesktop(device); onOpenRemote() },
        onRefresh = meshClient::refreshDevices,
        onOpenSettings = onOpenSettings, onSignIn = onSignIn, modifier = modifier,
    )
}

@Composable
internal fun DevicesContent(
    clientState: MeshClientState,
    favorites: List<FavoriteLink>,
    onAddFavorite: (String, String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onConnect: (MeshDevice) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.All) }
    val favoriteIds = favorites.map { it.url }.toSet()

    val signedIn = clientState.socketConnected
    val current = signedIn && clientState.devicesCurrent
    val effectiveFilter = if (!current && filter == DeviceFilter.Online) DeviceFilter.All else filter
    val onlineCount = clientState.devices.count { it.online }
    val visibleDevices = clientState.devices
        .filter { device ->
            val matchesFilter = when (effectiveFilter) {
                DeviceFilter.All -> true
                DeviceFilter.Online -> device.online
                DeviceFilter.Favorites -> device.nodeId in favoriteIds
            }
            matchesFilter
        }
        .groupBy { it.meshId }

    Column(modifier = modifier.fillMaxSize()) {
        MeshScreenHeader(
            title = stringResource(R.string.devices_title),
            subtitle = when {
                !current && clientState.devices.isNotEmpty() -> stringResource(R.string.devices_saved_count, clientState.devices.size)
                !signedIn -> stringResource(R.string.devices_subtitle_signed_out)
                clientState.devices.isEmpty() -> stringResource(R.string.devices_subtitle_empty)
                else -> stringResource(
                    R.string.devices_subtitle_counts,
                    onlineCount,
                    clientState.devices.size,
                    clientState.serverName ?: clientState.userId ?: "",
                )
            },
            actions = {
                IconButton(onClick = onRefresh, enabled = !clientState.requiresSignIn) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.devices_action_refresh),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.devices_action_settings),
                    )
                }
            },
        )

        when {
            !signedIn && clientState.requiresSignIn -> EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.devices_sign_in_title),
                description = stringResource(R.string.devices_sign_in_description),
                actionLabel = stringResource(R.string.devices_sign_in_action),
                onAction = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            )

            clientState.devices.isEmpty() && clientState.connectionState in setOf(ConnectionState.Connecting, ConnectionState.Reconnecting) ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MeshLoadingIndicator()
                }

            clientState.devices.isEmpty() -> EmptyState(
                icon = Icons.Outlined.DesktopWindows,
                title = stringResource(R.string.devices_empty_title),
                description = clientState.lastError
                    ?: stringResource(R.string.devices_empty_description),
                actionLabel = stringResource(R.string.devices_empty_action),
                onAction = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> {
                FilterRow(
                    filter = effectiveFilter,
                    onFilterChange = { filter = it },
                    totalCount = clientState.devices.size,
                    onlineCount = onlineCount,
                    current = current,
                    favoritesCount = clientState.devices.count { it.nodeId in favoriteIds },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                if (!current) {
                    ConnectionNotice(
                        message = if (signedIn) stringResource(R.string.devices_syncing) else
                            listOfNotNull(clientState.lastError, stringResource(R.string.devices_stale)).joinToString("\n"),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        onRetry = onRefresh,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (visibleDevices.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.Search,
                                title = stringResource(R.string.devices_nothing_found_title),
                                description = stringResource(R.string.devices_nothing_found_description),
                            )
                        }
                    }
                    visibleDevices.forEach { (meshId, devices) ->
                        item(key = "group-$meshId") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = clientState.groups.firstOrNull { it.id == meshId }?.name
                                        ?: meshId.ifBlank { stringResource(R.string.devices_group_ungrouped) },
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (!current) stringResource(R.string.devices_group_saved_count, devices.size) else stringResource(
                                        R.string.devices_group_counts,
                                        devices.count { it.online },
                                        devices.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(devices, key = { it.nodeId }) { device ->
                            DeviceCard(
                                device = device,
                                favorite = device.nodeId in favoriteIds,
                                statusCurrent = current,
                                onFavoriteChanged = { favorite ->
                                    if (favorite) {
                                        onAddFavorite(device.name, device.nodeId)
                                    } else {
                                        onRemoveFavorite(device.nodeId)
                                    }
                                },
                                onConnect = {
                                    onConnect(device)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: DeviceFilter,
    onFilterChange: (DeviceFilter) -> Unit,
    totalCount: Int,
    onlineCount: Int,
    current: Boolean,
    favoritesCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeviceFilter.entries.forEach { entry ->
            val count = when (entry) {
                DeviceFilter.All -> totalCount
                DeviceFilter.Online -> onlineCount
                DeviceFilter.Favorites -> favoritesCount
            }
            FilterChip(
                selected = filter == entry,
                enabled = current || entry != DeviceFilter.Online,
                onClick = { onFilterChange(entry) },
                shape = PillShape,
                label = { Text("${stringResource(entry.labelRes)} ${if (!current && entry == DeviceFilter.Online) "—" else count}") },
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: MeshDevice,
    favorite: Boolean,
    statusCurrent: Boolean,
    onFavoriteChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
) {
    val online = statusCurrent && device.online
    val canConnect = statusCurrent && device.canOpenNativeDesktop
    val statusColor = when {
        !statusCurrent -> MaterialTheme.colorScheme.outline
        online -> MeshTheme.colors.online
        else -> MaterialTheme.colorScheme.error
    }
    val status = stringResource(when {
        !statusCurrent -> R.string.connection_status_unknown
        device.online -> R.string.common_status_online
        else -> R.string.common_status_offline
    })
    val details = if (online && !device.canOpenNativeDesktop) {
        stringResource(R.string.devices_connect_unavailable, desktopCapabilityReason(device.desktopCapability))
    } else {
        listOfNotNull(
            device.osDescription.substringBefore(" - ").takeIf { it.isNotBlank() },
            device.ipAddress.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    val subtitle = details.ifBlank { status }
    MeshCard(modifier = Modifier.fillMaxWidth(), onClick = if (canConnect) onConnect else null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(40.dp).semantics { contentDescription = status }) {
                IconBadge(
                    icon = Icons.Outlined.DesktopWindows,
                    tint = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    container = if (online) MaterialTheme.colorScheme.primaryContainer else MeshTheme.colors.offlineContainer,
                    size = 40.dp,
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                        .background(statusColor, CircleShape),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onFavoriteChanged(!favorite) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(if (favorite) R.string.devices_favorite_remove else R.string.devices_favorite_add),
                    tint = if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canConnect) {
                FilledTonalIconButton(
                    onClick = onConnect,
                    shape = PillShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (device.viewOnly) Icons.Outlined.Visibility else Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(if (device.viewOnly) R.string.devices_action_view else R.string.devices_action_connect),
                    )
                }
            }
        }
    }
}
