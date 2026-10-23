package ru.papajon.meshcentralmobile.ui.screens.management

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DevicePowerAction
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.management.ManagedProcess
import ru.papajon.meshcentralmobile.management.ManagedService
import ru.papajon.meshcentralmobile.management.ServiceAction
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

enum class DeviceInspectorTab { Details, Services, Processes }

/** Stable semantics tags shared with the instrumentation tests for this screen. */
object DeviceInspectorTestTags {
    const val Screen = "device_inspector_screen"
    const val ContentList = "device_inspector_list"
    const val Back = "device_inspector_back"
    const val Refresh = "device_inspector_refresh"
    const val PowerMenu = "device_inspector_power_menu"
    const val Error = "device_inspector_error"
    const val ErrorRefresh = "device_inspector_error_refresh"
    const val ErrorHelp = "device_inspector_error_help"
    const val Pending = "device_inspector_pending"
    const val ConfirmDialog = "device_inspector_confirm_dialog"
    const val Confirm = "device_inspector_confirm"
    const val Cancel = "device_inspector_cancel"
    const val ServicesReadOnly = "device_inspector_services_read_only"
    const val SearchServices = "device_inspector_search_services"
    const val SearchProcesses = "device_inspector_search_processes"
    const val NoSearchResults = "device_inspector_no_search_results"

    fun tab(tab: DeviceInspectorTab) = "device_inspector_tab_${tab.name.lowercase()}"
    fun power(action: DevicePowerAction) = "device_inspector_power_${action.name.lowercase()}"
    fun service(name: String) = "device_inspector_service_$name"
    fun serviceMenu(name: String) = "device_inspector_service_menu_$name"
    fun serviceAction(name: String, action: ServiceAction) =
        "device_inspector_service_action_${name}_${action.name.lowercase()}"
    fun process(pid: Long) = "device_inspector_process_$pid"
}

private sealed interface PendingConfirmation {
    data class Power(val action: DevicePowerAction, val target: String) : PendingConfirmation
    data class Service(
        val name: String,
        val displayName: String,
        val action: ServiceAction,
    ) : PendingConfirmation
}

private data class InfoLine(val label: String, val value: String)

/**
 * Stateless device inspector.
 *
 * The caller owns every server interaction and every permission decision. This screen only turns
 * the supplied [snapshot], [services] and [processes] into a readable surface and asks for
 * confirmation before it invokes [onPower] or [onServiceAction]. [pendingAction] is a waiting
 * status, not a success acknowledgement: it is shown as such until the caller removes it.
 */
@Composable
fun DeviceInspectorScreen(
    snapshot: DeviceSystemSnapshot?,
    services: List<ManagedService>,
    processes: List<ManagedProcess>,
    loading: Boolean,
    error: String?,
    availablePowerActions: Set<DevicePowerAction>,
    canManageServices: Boolean,
    onRefresh: () -> Unit,
    onLoadServices: () -> Unit,
    onLoadProcesses: () -> Unit,
    onPower: (DevicePowerAction) -> Unit,
    onServiceAction: (String, ServiceAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pendingAction: String? = null,
    notice: String? = null,
    onOpenHelp: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    var selectedTab by rememberSaveable { mutableStateOf(DeviceInspectorTab.Details) }
    var powerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var serviceQuery by rememberSaveable { mutableStateOf("") }
    var processQuery by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<PendingConfirmation?>(null) }

    val spacing = MeshTheme.spacing
    val deviceLabel = snapshot?.deviceName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.manage_device_fallback)
    val unknownLabel = stringResource(R.string.manage_not_reported)
    val busy = loading || pendingAction?.isNotBlank() == true
    val errorMessage = error?.takeIf { it.isNotBlank() }
    val refreshSelectedTab: () -> Unit = {
        when (selectedTab) {
            DeviceInspectorTab.Details -> onRefresh()
            DeviceInspectorTab.Services -> onLoadServices()
            DeviceInspectorTab.Processes -> onLoadProcesses()
        }
    }
    val statusContent: (@Composable () -> Unit)? =
        if (errorMessage != null || busy || !notice.isNullOrBlank()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    if (!notice.isNullOrBlank()) Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (errorMessage != null) ErrorBanner(errorMessage, refreshSelectedTab, onOpenHelp, !busy)
                    if (busy) PendingBanner(pendingAction.orEmpty())
                }
            }
        } else null

    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            DeviceInspectorTab.Details -> Unit
            DeviceInspectorTab.Services -> onLoadServices()
            DeviceInspectorTab.Processes -> onLoadProcesses()
        }
    }

    Column(modifier.fillMaxSize().testTag(DeviceInspectorTestTags.Screen)) {
        InspectorHeader(
            deviceName = snapshot?.deviceName,
            osName = snapshot?.osName,
            loading = loading,
            powerActions = availablePowerActions,
            powerMenuExpanded = powerMenuExpanded,
            onPowerMenuExpandedChange = { powerMenuExpanded = it },
            onPowerRequest = { action ->
                powerMenuExpanded = false
                confirmation = PendingConfirmation.Power(action, deviceLabel)
            },
            onRefresh = refreshSelectedTab,
            onBack = onBack,
            busy = busy,
        )
        InspectorTabs(selected = selectedTab, enabled = !busy, onSelect = { selectedTab = it })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val contentModifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 900.dp)
                .fillMaxSize()
            when (selectedTab) {
                DeviceInspectorTab.Details -> DetailsTab(
                    snapshot = snapshot,
                    loading = loading,
                    statusContent = statusContent,
                    modifier = contentModifier,
                )
                DeviceInspectorTab.Services -> ServicesTab(
                    services = services,
                    loading = loading,
                    statusContent = statusContent,
                    canManageServices = canManageServices,
                    busy = busy,
                    query = serviceQuery,
                    onQueryChange = { serviceQuery = it.take(80) },
                    onLoad = onLoadServices,
                    onServiceActionRequest = { service, action ->
                        confirmation = PendingConfirmation.Service(
                            name = service.name,
                            displayName = service.displayName.ifBlank { service.name }.ifBlank { unknownLabel },
                            action = action,
                        )
                    },
                    modifier = contentModifier,
                )
                DeviceInspectorTab.Processes -> ProcessesTab(
                    processes = processes,
                    loading = loading,
                    statusContent = statusContent,
                    query = processQuery,
                    onQueryChange = { processQuery = it.take(80) },
                    onLoad = onLoadProcesses,
                    modifier = contentModifier,
                )
            }
        }
    }

    confirmation?.let { current ->
        when (current) {
            is PendingConfirmation.Power -> {
                val actionLabel = current.action.label()
                AlertDialog(
                    onDismissRequest = { confirmation = null },
                    modifier = Modifier.testTag(DeviceInspectorTestTags.ConfirmDialog),
                    title = {
                        Text(stringResource(R.string.manage_confirm_power_title, actionLabel, current.target))
                    },
                    text = { Text(current.action.explanation(current.target)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmation = null
                                onPower(current.action)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(DeviceInspectorTestTags.Confirm),
                        ) {
                            Text(actionLabel)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { confirmation = null },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(DeviceInspectorTestTags.Cancel),
                        ) {
                            Text(stringResource(R.string.manage_cancel))
                        }
                    },
                )
            }
            is PendingConfirmation.Service -> {
                val actionLabel = current.action.label()
                AlertDialog(
                    onDismissRequest = { confirmation = null },
                    modifier = Modifier.testTag(DeviceInspectorTestTags.ConfirmDialog),
                    title = {
                        Text(
                            stringResource(
                                R.string.manage_confirm_service_title,
                                actionLabel,
                                current.displayName,
                            ),
                        )
                    },
                    text = {
                        Text(
                            stringResource(
                                R.string.manage_confirm_service_message,
                                actionLabel,
                                current.displayName,
                                deviceLabel,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmation = null
                                onServiceAction(current.name, current.action)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(DeviceInspectorTestTags.Confirm),
                        ) {
                            Text(actionLabel)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { confirmation = null },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(DeviceInspectorTestTags.Cancel),
                        ) {
                            Text(stringResource(R.string.manage_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InspectorHeader(
    deviceName: String?,
    osName: String?,
    loading: Boolean,
    powerActions: Set<DevicePowerAction>,
    powerMenuExpanded: Boolean,
    onPowerMenuExpandedChange: (Boolean) -> Unit,
    onPowerRequest: (DevicePowerAction) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
) {
    val spacing = MeshTheme.spacing
    val title = deviceName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.manage_device_fallback)
    val refreshLabel = stringResource(R.string.manage_refresh)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.xs, end = spacing.sm, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .testTag(DeviceInspectorTestTags.Back),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.manage_back),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            osName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier
                .size(48.dp)
                .testTag(DeviceInspectorTestTags.Refresh)
                .semantics { contentDescription = refreshLabel },
        ) {
            if (loading) {
                MeshLoadingIndicator(modifier = Modifier.size(18.dp), size = 18.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                )
            }
        }
        if (powerActions.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = { onPowerMenuExpandedChange(true) },
                    enabled = !busy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(DeviceInspectorTestTags.PowerMenu),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PowerSettingsNew,
                        contentDescription = stringResource(R.string.manage_power_menu),
                    )
                }
                DropdownMenu(
                    expanded = powerMenuExpanded,
                    onDismissRequest = { onPowerMenuExpandedChange(false) },
                ) {
                    DevicePowerAction.entries
                        .filter { it in powerActions }
                        .forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label()) },
                                leadingIcon = {
                                    Icon(imageVector = action.icon(), contentDescription = null)
                                },
                                enabled = !busy,
                                onClick = {
                                    onPowerMenuExpandedChange(false)
                                    onPowerRequest(action)
                                },
                                modifier = Modifier.testTag(DeviceInspectorTestTags.power(action)),
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun InspectorTabs(
    selected: DeviceInspectorTab,
    enabled: Boolean,
    onSelect: (DeviceInspectorTab) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 4.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DeviceInspectorTab.entries.forEach { tab ->
            Tab(
                enabled = enabled,
                selected = selected == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(DeviceInspectorTestTags.tab(tab)),
                text = { Text(stringResource(tab.labelRes())) },
            )
        }
    }
}

@Composable
private fun DetailsTab(
    snapshot: DeviceSystemSnapshot?,
    loading: Boolean,
    statusContent: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    if (snapshot == null || !snapshot.hasReportedValues()) {
        LazyColumn(modifier.testTag(DeviceInspectorTestTags.ContentList), contentPadding = PaddingValues(MeshTheme.spacing.lg)) {
            if (statusContent != null) item(key = "status") { statusContent() }
            item(key = "empty") {
                if (loading) LoadingContent(Modifier.fillMaxWidth())
                else EmptyState(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.manage_no_details),
                    description = stringResource(R.string.manage_no_details_description),
                )
            }
        }
        return
    }

    val spacing = MeshTheme.spacing
    val nameLabel = stringResource(R.string.manage_label_name)
    val osLabel = stringResource(R.string.manage_label_os)
    val versionLabel = stringResource(R.string.manage_label_version)
    val architectureLabel = stringResource(R.string.manage_label_architecture)
    val kernelLabel = stringResource(R.string.manage_label_kernel)
    val cpuLabel = stringResource(R.string.manage_label_cpu)
    val modelLabel = stringResource(R.string.manage_label_model)

    val systemRows = buildList {
        snapshot.deviceName?.takeIf { it.isNotBlank() }?.let { add(InfoLine(nameLabel, it)) }
        snapshot.osName?.takeIf { it.isNotBlank() }?.let { add(InfoLine(osLabel, it)) }
        snapshot.osVersion?.takeIf { it.isNotBlank() }?.let { add(InfoLine(versionLabel, it)) }
        snapshot.architecture?.takeIf { it.isNotBlank() }?.let { add(InfoLine(architectureLabel, it)) }
        snapshot.kernelRelease?.takeIf { it.isNotBlank() }?.let { add(InfoLine(kernelLabel, it)) }
    }
    val hardwareRows = buildList {
        snapshot.cpu?.takeIf { it.isNotBlank() }?.let { add(InfoLine(cpuLabel, it)) }
        snapshot.model?.takeIf { it.isNotBlank() }?.let { add(InfoLine(modelLabel, it)) }
    }

    LazyColumn(
        modifier = modifier.imePadding().testTag(DeviceInspectorTestTags.ContentList),
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.sm,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (statusContent != null) item(key = "status") { statusContent() }
        item(key = "subtitle") {
            Text(
                text = stringResource(R.string.manage_details_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (systemRows.isNotEmpty()) {
            item(key = "system") {
                InfoSection(title = stringResource(R.string.manage_section_system)) {
                    systemRows.forEachIndexed { index, line ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        DetailRow(label = line.label, value = line.value)
                    }
                }
            }
        }
        if (hardwareRows.isNotEmpty()) {
            item(key = "hardware") {
                InfoSection(title = stringResource(R.string.manage_section_hardware)) {
                    hardwareRows.forEachIndexed { index, line ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        DetailRow(label = line.label, value = line.value)
                    }
                }
            }
        }
        item(key = "memory") { MemorySection(snapshot) }
        item(key = "disks") { DisksSection(snapshot) }
        item(key = "network") { NetworkSection(snapshot) }
    }
}

@Composable
private fun MemorySection(snapshot: DeviceSystemSnapshot) {
    val spacing = MeshTheme.spacing
    val capacityLabel = stringResource(R.string.manage_memory_capacity)
    val speedLabel = stringResource(R.string.manage_memory_speed)
    InfoSection(title = stringResource(R.string.manage_section_memory)) {
        val total = snapshot.memoryTotalBytes
        if (total != null) {
            Text(
                text = stringResource(R.string.manage_memory_total, formatBytes(total)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (snapshot.memoryModules.isEmpty()) {
            Text(
                text = stringResource(R.string.manage_memory_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            snapshot.memoryModules.forEachIndexed { index, module ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = spacing.xs),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.manage_memory_module, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    DetailRow(
                        label = capacityLabel,
                        value = formatBytes(module.capacityBytes),
                    )
                    module.speed?.takeIf { it.isNotBlank() }?.let {
                        DetailRow(label = speedLabel, value = it)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisksSection(snapshot: DeviceSystemSnapshot) {
    val spacing = MeshTheme.spacing
    val capacityLabel = stringResource(R.string.manage_memory_capacity)
    val modelLabel = stringResource(R.string.manage_label_model)
    InfoSection(title = stringResource(R.string.manage_section_disks)) {
        if (snapshot.disks.isEmpty()) {
            Text(
                text = stringResource(R.string.manage_disks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            snapshot.disks.forEachIndexed { index, disk ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = spacing.xs),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = disk.name ?: stringResource(R.string.manage_disk_fallback, index + 1),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    disk.model?.takeIf { it.isNotBlank() && it != disk.name }?.let {
                        DetailRow(label = modelLabel, value = it)
                    }
                    DetailRow(label = capacityLabel, value = formatBytes(disk.capacityBytes))
                }
            }
        }
    }
}

@Composable
private fun NetworkSection(snapshot: DeviceSystemSnapshot) {
    val spacing = MeshTheme.spacing
    InfoSection(title = stringResource(R.string.manage_section_network)) {
        if (snapshot.networkAddresses.isEmpty()) {
            Text(
                text = stringResource(R.string.manage_network_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val interfaceFallback = stringResource(R.string.manage_label_interface)
            snapshot.networkAddresses.forEachIndexed { index, address ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = spacing.xs),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                val label = listOfNotNull(
                    address.interfaceName?.takeIf { it.isNotBlank() },
                    address.family?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").let { if (it.isBlank()) interfaceFallback else it }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = address.address, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ServicesTab(
    services: List<ManagedService>,
    loading: Boolean,
    statusContent: (@Composable () -> Unit)?,
    canManageServices: Boolean,
    busy: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onLoad: () -> Unit,
    onServiceActionRequest: (ManagedService, ServiceAction) -> Unit,
    modifier: Modifier,
) {
    if (loading && services.isEmpty()) {
        LazyColumn(modifier.testTag(DeviceInspectorTestTags.ContentList), contentPadding = PaddingValues(MeshTheme.spacing.lg)) {
            if (statusContent != null) item(key = "status") { statusContent() }
            item(key = "loading") { LoadingContent(Modifier.fillMaxWidth()) }
        }
        return
    }
    val spacing = MeshTheme.spacing
    val filtered = remember(services, query) { filterServices(services, query) }

    LazyColumn(
        modifier = modifier.imePadding().testTag(DeviceInspectorTestTags.ContentList),
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.sm,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (statusContent != null) item(key = "status") { statusContent() }
        if (services.isNotEmpty()) {
            item(key = "search") {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = stringResource(R.string.manage_search_services),
                    tag = DeviceInspectorTestTags.SearchServices,
                )
            }
        }
        if (!canManageServices) {
            item(key = "read-only") {
                ReadOnlyNotice()
            }
        }
        when {
            services.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.manage_services_empty),
                    description = stringResource(R.string.manage_services_empty_description),
                    actionLabel = stringResource(R.string.manage_load),
                    onAction = onLoad,
                )
            }
            filtered.isEmpty() -> item(key = "no-results") {
                NoSearchResults()
            }
            else -> items(items = filtered, key = { "service:${it.name}" }) { service ->
                ServiceRow(
                    service = service,
                    canManageServices = canManageServices,
                    busy = busy,
                    onRequest = onServiceActionRequest,
                )
            }
        }
    }
}

@Composable
private fun ProcessesTab(
    processes: List<ManagedProcess>,
    loading: Boolean,
    statusContent: (@Composable () -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier,
) {
    if (loading && processes.isEmpty()) {
        LazyColumn(modifier.testTag(DeviceInspectorTestTags.ContentList), contentPadding = PaddingValues(MeshTheme.spacing.lg)) {
            if (statusContent != null) item(key = "status") { statusContent() }
            item(key = "loading") { LoadingContent(Modifier.fillMaxWidth()) }
        }
        return
    }
    val spacing = MeshTheme.spacing
    val filtered = remember(processes, query) { filterProcesses(processes, query) }

    LazyColumn(
        modifier = modifier.imePadding().testTag(DeviceInspectorTestTags.ContentList),
        contentPadding = PaddingValues(
            start = spacing.lg,
            end = spacing.lg,
            top = spacing.sm,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (statusContent != null) item(key = "status") { statusContent() }
        if (processes.isNotEmpty()) {
            item(key = "search") {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = stringResource(R.string.manage_search_processes),
                    tag = DeviceInspectorTestTags.SearchProcesses,
                )
            }
        }
        when {
            processes.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.manage_processes_empty),
                    description = stringResource(R.string.manage_processes_empty_description),
                    actionLabel = stringResource(R.string.manage_load),
                    onAction = onLoad,
                )
            }
            filtered.isEmpty() -> item(key = "no-results") {
                NoSearchResults()
            }
            else -> items(items = filtered, key = { it.pid }) { process ->
                ProcessRow(process)
            }
        }
    }
}

@Composable
private fun ServiceRow(
    service: ManagedService,
    canManageServices: Boolean,
    busy: Boolean,
    onRequest: (ManagedService, ServiceAction) -> Unit,
) {
    var menuExpanded by remember(service.name) { mutableStateOf(false) }
    val spacing = MeshTheme.spacing
    val displayLabel = service.displayName.takeIf { it.isNotBlank() }
        ?: service.name.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.manage_not_reported)
    val actionsLabel = stringResource(R.string.manage_service_actions, displayLabel)

    MeshCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DeviceInspectorTestTags.service(service.name)),
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = Icons.Outlined.Settings,
                size = 40.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = displayLabel, style = MaterialTheme.typography.titleSmall)
                if (displayLabel != service.name && service.name.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.manage_service_name, service.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                service.state.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.manage_service_state, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                service.startMode?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.manage_service_start_mode, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canManageServices && service.name.isNotBlank()) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !busy,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(DeviceInspectorTestTags.serviceMenu(service.name)),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = actionsLabel,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        ServiceAction.entries.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label()) },
                                leadingIcon = {
                                    Icon(imageVector = action.icon(), contentDescription = null)
                                },
                                enabled = !busy,
                                onClick = {
                                    menuExpanded = false
                                    onRequest(service, action)
                                },
                                modifier = Modifier.testTag(
                                    DeviceInspectorTestTags.serviceAction(service.name, action),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(process: ManagedProcess) {
    val spacing = MeshTheme.spacing
    val cpuLabel = stringResource(R.string.manage_cpu)
    val memoryLabel = stringResource(R.string.manage_memory)
    MeshCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DeviceInspectorTestTags.process(process.pid)),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = process.name.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.manage_not_reported),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.manage_process_pid, process.pid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            process.cpu?.takeIf { it.isNotBlank() }?.let {
                DetailRow(label = cpuLabel, value = it)
            }
            process.memory?.takeIf { it.isNotBlank() }?.let {
                DetailRow(label = memoryLabel, value = it)
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
        singleLine = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.manage_clear_search),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun NoSearchResults() {
    Text(
        text = stringResource(R.string.manage_no_search_results),
        modifier = Modifier.testTag(DeviceInspectorTestTags.NoSearchResults),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReadOnlyNotice() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DeviceInspectorTestTags.ServicesReadOnly),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.manage_services_read_only),
            modifier = Modifier.padding(MeshTheme.spacing.md),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorBanner(
    message: String,
    onRefresh: () -> Unit,
    onOpenHelp: (() -> Unit)?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DeviceInspectorTestTags.Error)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.manage_error),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = message, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TextButton(
                        onClick = onRefresh,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(DeviceInspectorTestTags.ErrorRefresh),
                    ) { Text(stringResource(R.string.manage_refresh)) }
                    if (onOpenHelp != null) TextButton(
                        onClick = onOpenHelp,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(DeviceInspectorTestTags.ErrorHelp),
                    ) { Text(stringResource(R.string.workspace_help)) }
                }
            }
        }
    }
}

@Composable
private fun PendingBanner(action: String, modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DeviceInspectorTestTags.Pending)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            MeshLoadingIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                size = 18.dp,
            )
            Text(
                text = stringResource(R.string.manage_pending, action),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md),
        ) {
            MeshLoadingIndicator()
            Text(
                text = stringResource(R.string.manage_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    MeshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MeshTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun formatBytes(bytes: Long?): String {
    val context = LocalContext.current
    val known = bytes?.takeIf { it > 0L }
    return if (known != null) {
        remember(known, context) { Formatter.formatShortFileSize(context, known) }
    } else {
        stringResource(R.string.manage_not_reported)
    }
}

private fun DeviceSystemSnapshot.hasReportedValues(): Boolean =
    !deviceName.isNullOrBlank() ||
        !osName.isNullOrBlank() ||
        !osVersion.isNullOrBlank() ||
        !architecture.isNullOrBlank() ||
        !kernelRelease.isNullOrBlank() ||
        !cpu.isNullOrBlank() ||
        !model.isNullOrBlank() ||
        memoryModules.isNotEmpty() ||
        disks.isNotEmpty() ||
        networkAddresses.isNotEmpty()

private fun filterServices(services: List<ManagedService>, query: String): List<ManagedService> {
    val needle = query.trim()
    if (needle.isEmpty()) return services
    return services.filter { service ->
        service.name.contains(needle, ignoreCase = true) ||
            service.displayName.contains(needle, ignoreCase = true) ||
            service.state.contains(needle, ignoreCase = true) ||
            service.startMode?.contains(needle, ignoreCase = true) == true
    }
}

private fun filterProcesses(processes: List<ManagedProcess>, query: String): List<ManagedProcess> {
    val needle = query.trim()
    if (needle.isEmpty()) return processes
    return processes.filter { process ->
        process.name.contains(needle, ignoreCase = true) ||
            process.pid.toString().contains(needle, ignoreCase = true)
    }
}

private fun DeviceInspectorTab.labelRes(): Int = when (this) {
    DeviceInspectorTab.Details -> R.string.manage_tab_details
    DeviceInspectorTab.Services -> R.string.manage_tab_services
    DeviceInspectorTab.Processes -> R.string.manage_tab_processes
}

@Composable
private fun DevicePowerAction.label(): String = stringResource(
    when (this) {
        DevicePowerAction.Wake -> R.string.manage_action_wake
        DevicePowerAction.Restart -> R.string.manage_action_restart
        DevicePowerAction.Shutdown -> R.string.manage_action_shutdown
        DevicePowerAction.Sleep -> R.string.manage_action_sleep
    },
)

private fun DevicePowerAction.icon(): ImageVector = when (this) {
    DevicePowerAction.Wake -> Icons.Outlined.Wifi
    DevicePowerAction.Restart -> Icons.Outlined.RestartAlt
    DevicePowerAction.Shutdown -> Icons.Outlined.PowerSettingsNew
    DevicePowerAction.Sleep -> Icons.Outlined.Bedtime
}

@Composable
private fun DevicePowerAction.explanation(target: String): String = stringResource(
    when (this) {
        DevicePowerAction.Wake -> R.string.manage_confirm_wake_message
        DevicePowerAction.Restart -> R.string.manage_confirm_restart_message
        DevicePowerAction.Shutdown -> R.string.manage_confirm_shutdown_message
        DevicePowerAction.Sleep -> R.string.manage_confirm_sleep_message
    },
    target,
)

@Composable
private fun ServiceAction.label(): String = stringResource(
    when (this) {
        ServiceAction.Start -> R.string.manage_action_start
        ServiceAction.Stop -> R.string.manage_action_stop
        ServiceAction.Restart -> R.string.manage_action_restart
    },
)

private fun ServiceAction.icon(): ImageVector = when (this) {
    ServiceAction.Start -> Icons.Outlined.PlayArrow
    ServiceAction.Stop -> Icons.Outlined.Stop
    ServiceAction.Restart -> Icons.Outlined.RestartAlt
}
