package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.tasks.*
import ru.papajon.meshcentralmobile.mesh.MeshDevice

@Composable
fun TaskToolsScreen(controller: TaskToolsController, device: MeshDevice, current: Boolean, services: Boolean,
    onBack: () -> Unit, screenVisible: Boolean = true, features2: Long? = controller.features2) {
    val state by controller.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, owner, screenVisible) {
        fun update() = controller.setActive(screenVisible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer); controller.setActive(false) }
    }
    LaunchedEffect(current, device.conn, device.rights) { controller.refresh() }
    TaskToolsContent(state, device, current, services, onBack, controller::refresh,
        controller::kill, controller::service, controller::details, controller::dismissDetails, controller::serviceDetails, controller::setConfirmationOpen, features2)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TaskToolsContent(state: TaskToolsState, device: MeshDevice, current: Boolean, services: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onKill: (AgentProcess, Boolean) -> Unit,
    onService: (AgentService, TaskServiceAction, Boolean) -> Unit, onDetails: (AgentProcess) -> Unit,
    onDismissDetails: () -> Unit, onServiceDetails: (AgentService) -> Unit = {}, onConfirmationChanged: (Boolean) -> Unit = {}, features2: Long? = null) {
    val screenContext = LocalContext.current
    var query by rememberSaveable(device.nodeId, services) { mutableStateOf("") }
    var byPid by rememberSaveable(device.nodeId) { mutableStateOf(false) }
    var filter by rememberSaveable(device.nodeId) { mutableIntStateOf(0) }
    var processConfirmation by remember(device.nodeId) { mutableStateOf<AgentProcess?>(null) }
    var serviceConfirmation by remember(device.nodeId) { mutableStateOf<Pair<AgentService, TaskServiceAction>?>(null) }
    val canRead = TaskToolsProtocol.canRead(device, current)
    val mutationAllowed = TaskToolsProtocol.canMutate(device, current, features2)
    val canChange = mutationAllowed && state.verificationReady
    val busy = state.loading || state.mutating || state.detailsLoading
    val confirmationBusy = state.mutating || state.detailsLoading
    var processes by remember { mutableStateOf(emptyList<AgentProcess>()) }
    LaunchedEffect(state.processes, query, byPid) {
        processes = withContext(Dispatchers.Default) {
            state.processes.filter { it.pid > 0 }.filter { query.isBlank() || it.name.contains(query, true) || it.pid.toString().contains(query) ||
                it.user?.contains(query, true) == true || it.command?.contains(query, true) == true }
                .let { if (byPid) it.sortedBy(AgentProcess::pid) else it.sortedWith(compareBy<AgentProcess> { p -> p.name.lowercase(java.util.Locale.ROOT) }.thenBy { p -> p.pid }) }
        }
    }
    var serviceRows by remember { mutableStateOf(emptyList<AgentService>()) }
    LaunchedEffect(state.services, query, filter) {
        serviceRows = withContext(Dispatchers.Default) {
            state.services.filter { (query.isBlank() || it.name.contains(query, true) || it.displayName.contains(query, true)) &&
                (filter == 0 || filter == 1 && it.running == true || filter == 2 && it.running == false || filter == 3 && it.running == null) }
                .sortedBy { it.displayName.lowercase(java.util.Locale.ROOT) }
        }
    }
    Column(Modifier.fillMaxSize().testTag(if (services) "tasks_services_screen" else "tasks_processes_screen")) {
        TopAppBar(title = { Text(stringResource(if (services) R.string.tasks_services else R.string.tasks_processes)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.device_back)) } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("tasks_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(if (services) R.string.tasks_service_search else R.string.tasks_search)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("tasks_search"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (services) {
                            listOf(R.string.tasks_all, R.string.tasks_running, R.string.tasks_stopped, R.string.tasks_unknown).forEachIndexed { i, label ->
                                FilterChip(selected = filter == i, onClick = { filter = i }, modifier = Modifier.testTag("tasks_filter_$i"), label = { Text(stringResource(label)) })
                            }
                        } else {
                            FilterChip(!byPid, { byPid = false }, label = { Text(stringResource(R.string.tasks_sort_name)) })
                            FilterChip(byPid, { byPid = true }, label = { Text(stringResource(R.string.tasks_sort_pid)) })
                        }
                        TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.testTag("tasks_refresh")) { Text(stringResource(R.string.tasks_refresh)) }
                    }
                    if (state.error == null) Text(stringResource(R.string.tasks_auto_refresh), style = MaterialTheme.typography.bodySmall)
                    if (!current || !device.connectivityKnown || device.conn and 1 == 0) Text(stringResource(R.string.tasks_offline), Modifier.testTag("tasks_offline"))
                    else if (!canRead) Text(stringResource(R.string.tasks_denied))
                    else if (!mutationAllowed) Text(stringResource(R.string.tasks_read_only), Modifier.testTag("tasks_read_only"))
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.tasks_loading)) }
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tasks_error").semantics { liveRegion = LiveRegionMode.Polite }) }
                    state.notice?.let { Text(stringResource(it), modifier = Modifier.testTag("tasks_notice").semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (state.loaded && (if (services) serviceRows.isEmpty() else processes.isEmpty())) Text(stringResource(R.string.tasks_empty))
                }
            }
            if (canRead && services) items(serviceRows, key = { it.name }, contentType = { "service" }) { service ->
                Column(Modifier.fillMaxWidth().testTag("service_${service.name}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                    if (service.displayName != service.name) Text(service.name, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(when { service.observedNotRunning -> R.string.tasks_not_running; service.running == true -> R.string.tasks_running; service.running == false -> R.string.tasks_stopped; else -> R.string.tasks_unknown }))
                    Text(stringResource(R.string.tasks_start_type, TaskToolsPresentation.startup(screenContext, service.startType)), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val detailsLabel = stringResource(R.string.manage_tab_details)
                        TextButton(onClick = { onServiceDetails(service) }, enabled = !busy,
                            modifier = Modifier.testTag("service_details_${service.name}").semantics { contentDescription = "$detailsLabel: ${service.displayName}" }) { Text(detailsLabel) }
                        if (canChange) TaskServiceAction.entries.forEach { action ->
                            val actionLabel = stringResource(action.label())
                            TextButton(onClick = { onConfirmationChanged(true); serviceConfirmation = service to action }, enabled = !busy,
                                modifier = Modifier.testTag("service_${action.name}_${service.name}").semantics { contentDescription = "$actionLabel: ${service.displayName}" }) { Text(actionLabel) }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (canRead && !services) items(processes, key = { it.pid }, contentType = { "process" }) { process ->
                Column(Modifier.fillMaxWidth().testTag("process_${process.pid}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(process.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tasks_pid_user, process.pid, process.user ?: stringResource(R.string.tasks_unknown)), style = MaterialTheme.typography.bodySmall)
                    process.command?.takeIf { it != process.name }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val detailsLabel = stringResource(R.string.tasks_details)
                        val killLabel = stringResource(R.string.tasks_kill)
                        TextButton(onClick = { onDetails(process) }, enabled = !state.detailsLoading && !state.mutating,
                            modifier = Modifier.testTag("details_${process.pid}").semantics { contentDescription = "$detailsLabel: ${process.name}, PID ${process.pid}" }) { Text(detailsLabel) }
                        if (canChange) TextButton(onClick = { onConfirmationChanged(true); processConfirmation = process }, enabled = !busy,
                            modifier = Modifier.testTag("kill_${process.pid}").semantics { contentDescription = "$killLabel: ${process.name}, PID ${process.pid}" }) { Text(killLabel, color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    processConfirmation?.let { target ->
        AlertDialog(onDismissRequest = { processConfirmation = null; onConfirmationChanged(false) }, title = { Text(screenContext.getString(R.string.tasks_kill)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(screenContext.getString(R.string.tasks_kill_confirm, target.name, target.pid))
                if (TaskToolsProtocol.critical(target)) Text(screenContext.getString(R.string.tasks_critical_process), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tasks_critical"))
            } },
            confirmButton = { TextButton(onClick = { onKill(target, true); processConfirmation = null; onConfirmationChanged(false) }, enabled = canChange && !confirmationBusy, modifier = Modifier.testTag("tasks_confirm")) { Text(screenContext.getString(R.string.tasks_confirm)) } },
            dismissButton = { TextButton(onClick = { processConfirmation = null; onConfirmationChanged(false) }) { Text(screenContext.getString(R.string.tasks_cancel)) } })
    }
    serviceConfirmation?.let { (target, action) ->
        AlertDialog(onDismissRequest = { serviceConfirmation = null; onConfirmationChanged(false) }, title = { Text(screenContext.getString(action.label())) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(screenContext.getString(R.string.tasks_service_confirm, screenContext.getString(action.label()), target.displayName))
                Text(target.name)
                if (TaskToolsProtocol.critical(target)) Text(screenContext.getString(R.string.tasks_critical_service), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tasks_critical"))
            } },
            confirmButton = { TextButton(onClick = { onService(target, action, true); serviceConfirmation = null; onConfirmationChanged(false) }, enabled = canChange && !confirmationBusy, modifier = Modifier.testTag("tasks_confirm")) { Text(screenContext.getString(R.string.tasks_confirm)) } },
            dismissButton = { TextButton(onClick = { serviceConfirmation = null; onConfirmationChanged(false) }) { Text(screenContext.getString(R.string.tasks_cancel)) } })
    }
    if (state.details != null) AlertDialog(onDismissRequest = onDismissDetails, title = { Text(screenContext.getString(R.string.tasks_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.detailsLoading) Text(screenContext.getString(R.string.tasks_loading))
            state.detailsError?.let { Text(screenContext.getString(it)) }
            TaskToolsPresentation.details(screenContext, state.details).forEach { (label, value) -> Text("$label: $value") }
        } }, confirmButton = { TextButton(onClick = onDismissDetails) { Text(screenContext.getString(R.string.tasks_close)) } })
    state.serviceDetails?.let { service ->
        AlertDialog(onDismissRequest = onDismissDetails, title = { Text(service.displayName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.detailsLoading) Text(screenContext.getString(R.string.tasks_loading))
                state.detailsError?.let { Text(screenContext.getString(it)) }
                Text(screenContext.getString(R.string.manage_service_name, service.name))
                Text(screenContext.getString(R.string.manage_service_state, screenContext.getString(when (service.running) {
                    true -> R.string.tasks_running; false -> R.string.tasks_stopped; null -> R.string.tasks_unknown
                })))
                Text(screenContext.getString(R.string.tasks_start_type, TaskToolsPresentation.startup(screenContext, service.startType)))
                service.pid?.let { Text(screenContext.getString(R.string.manage_process_pid, it)) }
            } }, confirmButton = { TextButton(onClick = onDismissDetails) { Text(screenContext.getString(R.string.tasks_close)) } })
    }
}

private fun TaskServiceAction.label(): Int = when (this) {
    TaskServiceAction.Start -> R.string.tasks_start
    TaskServiceAction.Stop -> R.string.tasks_stop
    TaskServiceAction.Restart -> R.string.tasks_restart
}
