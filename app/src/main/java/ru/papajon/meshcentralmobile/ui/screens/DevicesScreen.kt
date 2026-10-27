package ru.papajon.meshcentralmobile.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.discovery.DeviceSearch
import ru.papajon.meshcentralmobile.discovery.NeedsAttentionFilter
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.DevicePage
import ru.papajon.meshcentralmobile.mesh.DeviceBrowserFilter as DeviceFilter
import ru.papajon.meshcentralmobile.mesh.DeviceListQuery
import ru.papajon.meshcentralmobile.mesh.DeviceListPolicy
import androidx.compose.ui.res.pluralStringResource
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.ui.components.ConnectionNotice
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.components.desktopCapabilityReason
import ru.papajon.meshcentralmobile.ui.theme.MeshMono
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

private val DeviceFilter.labelRes: Int
    get() = when (this) {
        DeviceFilter.All -> R.string.devices_filter_all
        DeviceFilter.Online -> R.string.devices_filter_online
        DeviceFilter.Favorites -> R.string.devices_filter_favorites
        DeviceFilter.Recent -> R.string.browser_filter_recent
        DeviceFilter.NeedsAttention -> R.string.parity_needs_attention
    }

private const val MAX_SEARCH_LENGTH = 200

/**
 * Computer list: the app's home. A prominent connection context, compact filters and
 * grouped rows keep the list dense while favorite and connect stay separate, labelled targets.
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
    onOpenDevice: ((MeshDevice) -> Unit)? = null,
    recentDeviceIds: List<String> = emptyList(),
    onClearRecents: () -> Unit = {},
    onConnectDevice: ((MeshDevice) -> Unit)? = null,
    updateBanner: (@Composable () -> Unit)? = null,
) {
    val clientState by meshClient.clientState.collectAsState()
    var invitationGroup by remember { mutableStateOf<String?>(null) }
    invitationGroup?.let { id ->
        ru.papajon.meshcentralmobile.ui.screens.management.DeviceInviteDialog(meshClient, id) { invitationGroup = null }
    }
    DisposableEffect(meshClient) {
        meshClient.setDeviceListVisible(true)
        onDispose { meshClient.setDeviceListVisible(false) }
    }
    DevicesContent(
        clientState, favorites, onAddFavorite, onRemoveFavorite,
        onConnect = onConnectDevice ?: { device -> meshClient.connectDesktop(device); onOpenRemote() },
        onRefresh = meshClient::retryDevices,
        onOpenSettings = onOpenSettings, onSignIn = onSignIn, modifier = modifier,
        onOpenDevice = onOpenDevice,
        recentDeviceIds = recentDeviceIds,
        onClearRecents = onClearRecents,
        updateBanner = updateBanner,
        onInvite = { invitationGroup = it },
        onServerQuery = meshClient::searchDevices,
        onBrowserFilterChange = meshClient::setDeviceBrowserFilter,
        onBrowserSearchChange = meshClient::setDeviceBrowserSearch,
        onPage = meshClient::changeDevicePage,
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
    onOpenDevice: ((MeshDevice) -> Unit)? = null,
    recentDeviceIds: List<String> = emptyList(),
    onClearRecents: () -> Unit = {},
    updateBanner: (@Composable () -> Unit)? = null,
    onInvite: ((String) -> Unit)? = null,
    onServerQuery: (String, Boolean, Set<String>?) -> Unit = { _, _, _ -> },
    onBrowserFilterChange: (DeviceFilter) -> Unit = {},
    onBrowserSearchChange: (String) -> Unit = {},
    onPage: (Boolean) -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(clientState.deviceBrowserFilter) }
    var query by rememberSaveable { mutableStateOf(clientState.deviceBrowserSearch ?: clientState.deviceQuery.filter) }
    val favoriteIds = remember(favorites) { favorites.map { it.url }.toSet() }
    val page = clientState.devicePage
    val pageDevices = remember(clientState.devices, page.nodeIds) {
        page.nodeIds?.let { ids -> clientState.devices.filter { it.nodeId in ids } } ?: clientState.devices
    }
    val serverSearch = page.serverLimited
    LaunchedEffect(filter) { onBrowserFilterChange(filter) }
    LaunchedEffect(query) { onBrowserSearchChange(query) }

    val signedIn = clientState.socketConnected
    val current = signedIn && clientState.devicesCurrent
    val effectiveFilter = if (!serverSearch && !current && filter == DeviceFilter.Online) DeviceFilter.All else filter
    val onlineCount = remember(pageDevices) { pageDevices.count { it.online } }
    val recentOrder = remember(recentDeviceIds) { recentDeviceIds.withIndex().associate { it.value to it.index } }
    val recentCount = pageDevices.count { it.nodeId in recentOrder }
    val queryActive = DeviceSearch.hasQuery(query)
    val request = DeviceListQuery(query,
        collect = effectiveFilter == DeviceFilter.Online || effectiveFilter == DeviceFilter.NeedsAttention,
        ids = when (effectiveFilter) {
            DeviceFilter.Favorites -> favoriteIds
            DeviceFilter.Recent -> recentDeviceIds.toSet()
            else -> null
        }).normalized()
    LaunchedEffect(request, serverSearch, signedIn) {
        if (serverSearch && signedIn && DeviceListPolicy.queryChanged(request, clientState.deviceQuery)) {
            delay(300)
            onServerQuery(request.filter, request.collect, request.ids)
        }
    }
    val hidingRows = serverSearch && page.loading
    val pageFailed = (clientState.lastError as? ru.papajon.meshcentralmobile.localization.UiText.Resource)?.id == R.string.devices_page_failed
    val subtitle = when {
        !current && pageDevices.isNotEmpty() -> stringResource(R.string.devices_saved_count, pageDevices.size)
        !signedIn -> stringResource(R.string.devices_subtitle_signed_out)
        serverSearch -> page.totalCount?.let { stringResource(R.string.devices_server_total, it) }
            ?: stringResource(R.string.common_status_online)
        pageDevices.isEmpty() -> stringResource(R.string.common_status_online)
        else -> stringResource(
            R.string.devices_subtitle_counts,
            onlineCount,
            pageDevices.size,
            clientState.serverName ?: clientState.userId ?: "",
        )
    }
    val visibleDevices = remember(pageDevices, clientState.groups, effectiveFilter, favoriteIds, recentOrder, query, serverSearch, page.filter,
        current, clientState.agentInstallAllowed, clientState.serverCapabilities) {
        pageDevices
        .filter { device ->
            when (effectiveFilter) {
                DeviceFilter.All -> true
                DeviceFilter.Online -> device.online
                DeviceFilter.Favorites -> device.nodeId in favoriteIds
                DeviceFilter.Recent -> device.nodeId in recentOrder
                DeviceFilter.NeedsAttention -> NeedsAttentionFilter.matches(device)
            }
        }
        .let { devices -> if (serverSearch) devices
            else DeviceSearch.filter(devices, query, clientState.groups) }
        .let { devices ->
            if (effectiveFilter == DeviceFilter.Recent) {
                devices.sortedBy { device -> recentOrder[device.nodeId] ?: Int.MAX_VALUE }
            } else {
                devices
            }
        }
        .groupBy { if (effectiveFilter == DeviceFilter.Recent) "" else it.meshId }
        .toMutableMap().apply {
            if (!serverSearch && effectiveFilter == DeviceFilter.All && query.isBlank() && current) {
                clientState.groups.filter { ru.papajon.meshcentralmobile.management.DeviceAdmin.canInvite(it, clientState) }
                    .forEach { putIfAbsent(it.id, emptyList()) }
            }
        }
    }
    val emptyTitleRes = when {
        queryActive -> R.string.browser_search_empty_title
        effectiveFilter == DeviceFilter.Recent -> R.string.browser_recent_empty_title
        effectiveFilter == DeviceFilter.Favorites -> R.string.browser_favorites_empty_title
        else -> R.string.browser_filter_empty_title
    }
    val emptyDescriptionRes = when {
        queryActive -> R.string.browser_search_empty_description
        effectiveFilter == DeviceFilter.Recent -> R.string.browser_recent_empty_description
        effectiveFilter == DeviceFilter.Favorites -> R.string.browser_favorites_empty_description
        else -> R.string.browser_filter_empty_description
    }

    Column(modifier = modifier.fillMaxSize()) {
        DevicesHeader(
            subtitle = subtitle,
            signedIn = signedIn,
            current = current,
            refreshEnabled = !clientState.requiresSignIn,
            onRefresh = onRefresh,
            onSettings = onOpenSettings,
        )

        if (pageDevices.isEmpty() || !signedIn && clientState.requiresSignIn) updateBanner?.invoke()

        when {
            !signedIn && clientState.requiresSignIn -> EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.devices_sign_in_title),
                description = stringResource(R.string.devices_sign_in_description),
                actionLabel = stringResource(R.string.devices_sign_in_action),
                onAction = onSignIn,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            )

            pageDevices.isEmpty() && clientState.connectionState in setOf(ConnectionState.Connecting, ConnectionState.Reconnecting) ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MeshLoadingIndicator()
                        clientState.message?.let { Text(it.asString(), modifier = Modifier.padding(horizontal = 20.dp)) }
                    }
                }

            pageDevices.isEmpty() && visibleDevices.isEmpty() && !serverSearch -> EmptyState(
                icon = Icons.Outlined.DesktopWindows,
                title = stringResource(R.string.devices_empty_title),
                description = clientState.lastError?.asString()
                    ?: stringResource(R.string.devices_empty_description),
                actionLabel = stringResource(R.string.devices_empty_action),
                onAction = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            )

            else -> {
                // Everything above the devices scrolls with the single LazyColumn, so short
                // screens can always reach the cards instead of being clipped by fixed chrome.
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("devices-list"),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                ) {
                    if (updateBanner != null) item(key = "devices-update") { updateBanner() }
                    item(key = "devices-search") {
                        DeviceSearchField(query = query, onQueryChange = { query = it }, serverSearch = serverSearch)
                    }
                    item(key = "devices-filter") {
                        FilterRow(
                            filter = effectiveFilter,
                            onFilterChange = { filter = it; onBrowserFilterChange(it) },
                            totalCount = pageDevices.size,
                            onlineCount = onlineCount,
                            current = current,
                            favoritesCount = pageDevices.count { it.nodeId in favoriteIds },
                            recentCount = recentCount,
                            attentionCount = pageDevices.count(NeedsAttentionFilter::matches),
                            showCounts = !serverSearch || page.completeSnapshot && page.requestedIds == null && !page.loading,
                        )
                    }
                    if (serverSearch) item(key = "devices-pages") {
                        DevicePageControls(page, signedIn, onPage)
                    }
                    if (serverSearch && onInvite != null && clientState.groups.any {
                        ru.papajon.meshcentralmobile.management.DeviceAdmin.canInvite(it, clientState)
                    }) item(key = "devices-invite") {
                        var expanded by remember { mutableStateOf(false) }
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("devices-invite-group")) {
                                Text(stringResource(R.string.admin_invite))
                            }
                            androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                clientState.groups.filter { ru.papajon.meshcentralmobile.management.DeviceAdmin.canInvite(it, clientState) }.forEach { group ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(group.name) }, onClick = {
                                        expanded = false; onInvite(group.id)
                                    }, modifier = Modifier.testTag("devices-invite-target-${group.id}"))
                                }
                            }
                        }
                    }
                    if (hidingRows) item(key = "devices-page-loading") {
                        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MeshLoadingIndicator()
                            Text(stringResource(R.string.devices_syncing))
                        }
                    }
                    if (pageFailed) item(key = "devices-page-error") {
                        ConnectionNotice(stringResource(R.string.devices_page_failed), Modifier.padding(horizontal = 20.dp), onRetry = onRefresh)
                    }
                    if (effectiveFilter == DeviceFilter.Recent && recentDeviceIds.isNotEmpty()) {
                        item(key = "devices-clear-recent") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onClearRecents) {
                                    Text(stringResource(R.string.browser_recent_clear))
                                }
                            }
                        }
                    }
                    if (!current && !pageFailed) {
                        item(key = "devices-connection-notice") {
                            ConnectionNotice(
                                message = if (signedIn) stringResource(R.string.devices_syncing) else
                                    listOfNotNull(
                                        (clientState.message ?: clientState.lastError)?.asString(),
                                        stringResource(R.string.devices_stale),
                                    ).joinToString("\n"),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                onRetry = onRefresh,
                            )
                        }
                    }
                    if (visibleDevices.isEmpty() && !hidingRows) {
                        item(key = "devices-empty") {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                EmptyState(
                                    icon = when {
                                        queryActive -> Icons.Outlined.Search
                                        effectiveFilter == DeviceFilter.Favorites -> Icons.Outlined.StarBorder
                                        else -> Icons.Outlined.DesktopWindows
                                    },
                                    title = stringResource(emptyTitleRes),
                                    description = stringResource(emptyDescriptionRes),
                                )
                            }
                        }
                    }
                    if (!hidingRows) visibleDevices.forEach { (meshId, devices) ->
                        if (effectiveFilter != DeviceFilter.Recent) item(key = "group-$meshId") {
                            val reportedGroup = clientState.groups.firstOrNull { it.id == meshId }?.name
                            val groupName = when {
                                !reportedGroup.isNullOrBlank() -> reportedGroup
                                meshId.isNotBlank() -> meshId
                                else -> stringResource(R.string.devices_group_ungrouped)
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                GroupHeader(
                                    name = groupName,
                                    count = if (!current) stringResource(R.string.devices_group_saved_count, devices.size) else stringResource(
                                        R.string.devices_group_counts,
                                        devices.count { it.online },
                                        devices.size,
                                    ),
                                )
                                clientState.groups.firstOrNull { it.id == meshId }?.let { group ->
                                    if (onInvite != null && ru.papajon.meshcentralmobile.management.DeviceAdmin.canInvite(group, clientState)) {
                                        TextButton(onClick = { onInvite(group.id) }, modifier = Modifier.testTag("admin_invite_${group.id}")) {
                                            Text(stringResource(R.string.admin_invite))
                                        }
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            items = devices,
                            key = { _, device -> device.nodeId },
                        ) { index, device ->
                            val shape = when {
                                devices.size == 1 -> MaterialTheme.shapes.medium
                                index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                index == devices.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                                else -> RectangleShape
                            }
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                DeviceRow(
                                    device = device,
                                    favorite = device.nodeId in favoriteIds,
                                    statusCurrent = current,
                                    shape = shape,
                                    showDivider = index > 0,
                                    onFavoriteChanged = { favorite ->
                                        if (favorite) onAddFavorite(device.name, device.nodeId)
                                        else onRemoveFavorite(device.nodeId)
                                    },
                                    onConnect = { onConnect(device) },
                                    onOpenDetails = onOpenDevice?.let { callback -> { callback(device) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesHeader(
    subtitle: String,
    signedIn: Boolean,
    current: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val contextColor = when {
        !signedIn -> MaterialTheme.colorScheme.outline
        current -> MeshTheme.colors.online
        else -> MeshTheme.colors.warning
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        if (maxWidth < 420.dp && LocalDensity.current.fontScale >= 1.4f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DevicesHeading(subtitle, contextColor, Modifier.fillMaxWidth())
                DeviceHeaderActions(refreshEnabled, onRefresh, onSettings, Modifier.align(Alignment.End))
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            DevicesHeading(subtitle, contextColor, Modifier.weight(1f))
            DeviceHeaderActions(refreshEnabled, onRefresh, onSettings)
        }
    }
}

@Composable
private fun DevicesHeading(subtitle: String, contextColor: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(contextColor))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceHeaderActions(refreshEnabled: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        IconButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.devices_action_refresh))
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.devices_action_settings))
        }
    }
}

@Composable
private fun DeviceSearchField(query: String, onQueryChange: (String) -> Unit, serverSearch: Boolean = false) {
    val focusManager = LocalFocusManager.current
    val searchLabel = stringResource(R.string.browser_search_label)
    OutlinedTextField(
        value = query,
        onValueChange = { onQueryChange(it.take(if (serverSearch) 128 else MAX_SEARCH_LENGTH)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics { contentDescription = searchLabel },
        singleLine = true,
        shape = PillShape,
        label = { Text(searchLabel) },
        placeholder = { Text(stringResource(if (serverSearch) R.string.devices_server_search else R.string.browser_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.browser_search_clear),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun FilterRow(
    filter: DeviceFilter,
    onFilterChange: (DeviceFilter) -> Unit,
    totalCount: Int,
    onlineCount: Int,
    current: Boolean,
    favoritesCount: Int,
    recentCount: Int,
    attentionCount: Int,
    showCounts: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeviceFilter.entries.forEach { entry ->
            val count = when (entry) {
                DeviceFilter.All -> totalCount
                DeviceFilter.Online -> onlineCount
                DeviceFilter.Favorites -> favoritesCount
                DeviceFilter.Recent -> recentCount
                DeviceFilter.NeedsAttention -> attentionCount
            }
            FilterChip(
                modifier = Modifier.testTag("device_filter_${entry.name}"),
                selected = filter == entry,
                enabled = current || entry != DeviceFilter.Online,
                onClick = { onFilterChange(entry) },
                shape = PillShape,
                label = {
                    Text(if (!showCounts) stringResource(entry.labelRes) else "${stringResource(entry.labelRes)} ${if (!current && entry == DeviceFilter.Online) "—" else count}")
                },
            )
        }
    }
}

@Composable
private fun DevicePageControls(page: DevicePage, signedIn: Boolean, onPage: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        val start = if (page.returnedCount == 0) 0 else page.skip + 1
        val end = page.skip + page.returnedCount
        Text(if (page.totalCount != null) pluralStringResource(R.plurals.devices_page_count, page.totalCount, start, end, page.totalCount)
            else pluralStringResource(R.plurals.devices_page_count_unknown, page.returnedCount, start, end), style = MaterialTheme.typography.bodySmall)
        if (!page.completeSnapshot) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onPage(false) }, enabled = signedIn && !page.loading && page.hasPrevious,
                modifier = Modifier.weight(1f).testTag("devices-page-previous")) { Text(stringResource(R.string.devices_page_previous)) }
            TextButton(onClick = { onPage(true) }, enabled = signedIn && !page.loading && page.hasNext,
                modifier = Modifier.weight(1f).testTag("devices-page-next")) { Text(stringResource(R.string.devices_page_next)) }
        }
    }
}

@Composable
private fun GroupHeader(name: String, count: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(
    device: MeshDevice,
    favorite: Boolean,
    statusCurrent: Boolean,
    shape: Shape,
    showDivider: Boolean,
    onFavoriteChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onOpenDetails: (() -> Unit)? = null,
) {
    val online = statusCurrent && device.online
    val canConnect = statusCurrent && device.canOpenNativeDesktop
    val statusColor = when {
        !statusCurrent -> MaterialTheme.colorScheme.outline
        online -> MeshTheme.colors.online
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = stringResource(when {
        !statusCurrent -> R.string.connection_status_unknown
        device.online -> R.string.common_status_online
        else -> R.string.common_status_offline
    })
    val os = device.osDescription.substringBefore(" - ").trim()
    val rowAction = onOpenDetails ?: if (canConnect) onConnect else null
    val rowActionLabel = stringResource(
        if (onOpenDetails != null) R.string.device_open_details else R.string.devices_action_connect,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device-row-${device.nodeId}")
            .clip(shape)
            .then(
                rowAction?.let { action ->
                    Modifier.clickable(onClickLabel = rowActionLabel) { action() }
                } ?: Modifier,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    IconBadge(
                        icon = if (device.isAndroidAgent) Icons.Outlined.Smartphone else Icons.Outlined.DesktopWindows,
                        tint = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        container = if (online) MaterialTheme.colorScheme.primaryContainer else MeshTheme.colors.offlineContainer,
                        size = 40.dp,
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                            .background(statusColor, CircleShape)
                            .semantics { contentDescription = status },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                        )
                    }
                    if (os.isNotEmpty()) {
                        Text(
                            text = os,
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (device.ipAddress.isNotBlank()) {
                        Text(
                            text = device.ipAddress,
                            modifier = Modifier.padding(top = 2.dp),
                            style = MeshMono.copy(textDirection = TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (online && !device.canOpenNativeDesktop) {
                        Text(
                            text = stringResource(
                                R.string.devices_connect_unavailable,
                                desktopCapabilityReason(device.desktopCapability),
                            ),
                            modifier = Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                IconButton(
                    onClick = { onFavoriteChanged(!favorite) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (favorite) R.string.devices_favorite_remove else R.string.devices_favorite_add,
                        ),
                        tint = if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canConnect) {
                    FilledIconButton(
                        onClick = onConnect,
                        shape = PillShape,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (device.viewOnly) Icons.Outlined.Visibility else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (device.viewOnly) R.string.devices_action_view else R.string.devices_action_connect,
                            ),
                        )
                    }
                }
            }
        }
    }
}
