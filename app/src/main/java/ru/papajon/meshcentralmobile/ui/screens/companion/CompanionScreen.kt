package ru.papajon.meshcentralmobile.ui.screens.companion

import android.app.Activity
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.CompanionAssessment
import ru.papajon.meshcentralmobile.companion.CompanionController
import ru.papajon.meshcentralmobile.companion.CompanionCpu
import ru.papajon.meshcentralmobile.companion.CompanionDiagnostics
import ru.papajon.meshcentralmobile.companion.CompanionDisk
import ru.papajon.meshcentralmobile.companion.CompanionErrorCode
import ru.papajon.meshcentralmobile.companion.CompanionEvent
import ru.papajon.meshcentralmobile.companion.CompanionEventKind
import ru.papajon.meshcentralmobile.companion.CompanionFindings
import ru.papajon.meshcentralmobile.companion.CompanionJob
import ru.papajon.meshcentralmobile.companion.CompanionJobErrorCode
import ru.papajon.meshcentralmobile.companion.CompanionJobStatus
import ru.papajon.meshcentralmobile.companion.CompanionMemory
import ru.papajon.meshcentralmobile.companion.CompanionProcessItem
import ru.papajon.meshcentralmobile.companion.CompanionProcesses
import ru.papajon.meshcentralmobile.companion.CompanionReport
import ru.papajon.meshcentralmobile.companion.CompanionReportLines
import ru.papajon.meshcentralmobile.companion.CompanionReportWarningKind
import ru.papajon.meshcentralmobile.companion.CompanionServiceAction
import ru.papajon.meshcentralmobile.companion.CompanionServiceItem
import ru.papajon.meshcentralmobile.companion.CompanionServiceMutation
import ru.papajon.meshcentralmobile.companion.CompanionServices
import ru.papajon.meshcentralmobile.companion.CompanionTemplate
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshLoadingIndicator
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.components.StatusPill
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

object CompanionTestTags {
    const val Screen = "companion_screen"
    const val Content = "companion_content"
    const val Back = "companion_back"
    const val Refresh = "companion_refresh"
    const val RunDiagnostics = "companion_run_diagnostics"
    const val RunServices = "companion_run_services"
    const val RunProcesses = "companion_run_processes"
    const val DisclosureDialog = "companion_disclosure_dialog"
    const val DisclosureConfirm = "companion_disclosure_confirm"
    const val DisclosureCancel = "companion_disclosure_cancel"
    const val DiagnosticsSection = "companion_diagnostics_section"
    const val Findings = "companion_findings"
    const val ServicesSection = "companion_services_section"
    const val ProcessesSection = "companion_processes_section"
    const val ServicesSearch = "companion_services_search"
    const val ProcessesSearch = "companion_processes_search"
    const val ServiceConfirmDialog = "companion_service_confirm_dialog"
    const val ServiceConfirm = "companion_service_confirm"
    const val ServiceCancel = "companion_service_cancel"
    const val SelectedJob = "companion_selected_job"
    const val CancelJob = "companion_cancel_job"
    const val DeleteJob = "companion_delete_job"
    const val JobsSection = "companion_jobs_section"
    const val ClearHistory = "companion_clear_history"
    const val ClearHistoryDialog = "companion_clear_history_dialog"
    const val ClearHistoryConfirm = "companion_clear_history_confirm"
    const val ClearHistoryCancel = "companion_clear_history_cancel"
    const val EventsSection = "companion_events_section"
    const val PendingCard = "companion_pending_card"
    const val PendingRecheck = "companion_pending_recheck"
    const val PendingDiscard = "companion_pending_discard"
    const val ErrorCard = "companion_error_card"
    const val ShareReport = "companion_share_report"
    const val QuickHelp = "companion_quick_help"

    fun serviceRow(name: String): String = "companion_service_row_$name"
    fun serviceMenu(name: String): String = "companion_service_menu_$name"
    fun serviceAction(name: String, action: CompanionServiceAction): String =
        "companion_service_action_${name}_${action.name.lowercase()}"
    fun job(id: String): String = "companion_job_$id"
}

private data class ServiceTarget(
    val service: CompanionServiceItem,
    val action: CompanionServiceAction,
)

/**
 * Guided companion surface for one device.
 *
 * The screen reads only [CompanionController.state]; the controller owns every server call.
 * Read-only checks and service mutations are always explicit, and the only automatic request is
 * `getJob` polling while a selected job is active.
 */
@Composable
fun CompanionScreen(
    controller: CompanionController,
    deviceName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val state by controller.state.collectAsStateWithLifecycle()
    val spacing = MeshTheme.spacing
    val context = LocalContext.current
    var disclosureTemplate by remember { mutableStateOf<CompanionTemplate?>(null) }
    var serviceTarget by remember { mutableStateOf<ServiceTarget?>(null) }
    var clearHistoryConfirmation by remember { mutableStateOf(false) }
    var serviceQuery by remember { mutableStateOf("") }
    var processQuery by remember { mutableStateOf("") }

    LaunchedEffect(controller) { controller.refresh() }

    val diagnosticsJob = state.jobs.firstOrNull { it.template == CompanionTemplate.DIAGNOSTICS }
    val servicesJob = state.jobs.firstOrNull { it.template == CompanionTemplate.SERVICES }
    val processesJob = state.jobs.firstOrNull { it.template == CompanionTemplate.PROCESSES }
    val diagnostics = diagnosticsJob?.result as? CompanionDiagnostics
    val services = servicesJob?.result as? CompanionServices
    val processes = processesJob?.result as? CompanionProcesses
    val activeJob = state.jobs.firstOrNull { it.active }
    val errorCode = state.error
    val pendingOperation = state.pendingOperation
    val selectedJob = state.selectedJob
    val showPending = pendingOperation != null && !state.loading
    val canStart = state.available &&
        !state.loading &&
        pendingOperation == null &&
        activeJob == null

    val filteredServices = remember(services?.items, serviceQuery) {
        filterServices(services?.items.orEmpty(), serviceQuery)
    }
    val filteredProcesses = remember(processes?.items, processQuery) {
        filterProcesses(processes?.items.orEmpty(), processQuery)
    }
    val deviceLabel = deviceName.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.companion_device_fallback)

    val report = diagnosticsJob?.let { CompanionReport.from(it) }
    val reportText: String? = if (report != null) {
        val lines = companionReportLines(report)
        report.toShareText(lines)
    } else {
        null
    }
    val reportSubject = stringResource(R.string.companion_report_subject)
    val reportChooser = stringResource(R.string.companion_report_chooser)
    val shareReport: () -> Unit = {
        if (reportText != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, reportSubject)
                putExtra(Intent.EXTRA_TEXT, reportText)
            }
            val chooser = Intent.createChooser(send, reportChooser)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    Box(modifier.fillMaxSize().testTag(CompanionTestTags.Screen)) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 900.dp)
                .fillMaxSize(),
        ) {
            CompanionHeader(
                deviceName = deviceLabel,
                loading = state.loading,
                onRefresh = { controller.refresh() },
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .testTag(CompanionTestTags.Content),
                contentPadding = PaddingValues(
                    start = spacing.lg,
                    end = spacing.lg,
                    top = spacing.sm,
                    bottom = spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (!state.available && !state.loading) {
                    item(key = "unavailable") { UnavailableCard() }
                }
                if (errorCode != null && !(state.errorUncertain && showPending)) {
                    item(key = "error") {
                        CompanionErrorCard(
                            code = errorCode,
                            onDismiss = controller::dismissError,
                        )
                    }
                }
                if (showPending) {
                    item(key = "pending") {
                        PendingOperationCard(
                            template = pendingOperation!!.template,
                            code = errorCode,
                            onRecheck = { controller.recheckPendingOperation() },
                            onDiscard = controller::dismissPendingOperation,
                        )
                    }
                }

                item(key = "intro") { CompanionIntroCard() }
                item(key = "checks") {
                    CompanionChecksCard(
                        diagnosticsJob = diagnosticsJob,
                        servicesJob = servicesJob,
                        processesJob = processesJob,
                        canStart = canStart,
                        hasActiveJob = activeJob != null,
                        hasPendingOperation = pendingOperation != null && !state.loading,
                        onRun = { template -> disclosureTemplate = template },
                    )
                }
                if (diagnosticsJob != null) {
                    item(key = "diagnostics") {
                        DiagnosticsCard(
                            job = diagnosticsJob,
                            diagnostics = diagnostics,
                            canRun = canStart,
                            shareEnabled = reportText != null && diagnosticsJob.terminal,
                            onRunAgain = { disclosureTemplate = CompanionTemplate.DIAGNOSTICS },
                            onShare = shareReport,
                        )
                    }
                }
                if (servicesJob != null) {
                    item(key = "services_header") {
                        SectionHeader(
                            title = stringResource(R.string.companion_services_title),
                            subtitle = if (services?.truncated == true) {
                                stringResource(R.string.companion_services_truncated)
                            } else {
                                null
                            },
                        )
                    }
                    if (services != null && services.items.isNotEmpty()) {
                        item(key = "services_search") {
                            OutlinedTextField(
                                value = serviceQuery,
                                onValueChange = { serviceQuery = it.take(80) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag(CompanionTestTags.ServicesSearch),
                                singleLine = true,
                                label = { Text(stringResource(R.string.companion_service_search)) },
                            )
                        }
                    }
                    if (services == null) {
                        item(key = "services_no_result") {
                            Text(
                                text = stringResource(R.string.companion_services_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (services.items.isEmpty()) {
                        item(key = "services_empty") {
                            EmptyState(
                                icon = Icons.Outlined.Settings,
                                title = stringResource(R.string.companion_services_title),
                                description = stringResource(R.string.companion_services_empty),
                            )
                        }
                    } else if (filteredServices.isEmpty()) {
                        item(key = "services_no_matches") {
                            Text(
                                text = stringResource(R.string.companion_no_search_results),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(items = filteredServices) { service ->
                            ServiceRow(
                                service = service,
                                canStart = canStart,
                                onAction = { action ->
                                    serviceTarget = ServiceTarget(service, action)
                                },
                            )
                        }
                    }
                }
                if (processesJob != null) {
                    item(key = "processes_header") {
                        SectionHeader(
                            title = stringResource(R.string.companion_processes_title),
                            subtitle = if (processes?.truncated == true) {
                                stringResource(R.string.companion_processes_truncated)
                            } else {
                                null
                            },
                        )
                    }
                    if (processes != null && processes.items.isNotEmpty()) {
                        item(key = "processes_search") {
                            OutlinedTextField(
                                value = processQuery,
                                onValueChange = { processQuery = it.take(80) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag(CompanionTestTags.ProcessesSearch),
                                singleLine = true,
                                label = { Text(stringResource(R.string.companion_process_search)) },
                            )
                        }
                    }
                    if (processes == null) {
                        item(key = "processes_no_result") {
                            Text(
                                text = stringResource(R.string.companion_processes_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (processes.items.isEmpty()) {
                        item(key = "processes_empty") {
                            EmptyState(
                                icon = Icons.Outlined.Memory,
                                title = stringResource(R.string.companion_processes_title),
                                description = stringResource(R.string.companion_processes_empty),
                            )
                        }
                    } else if (filteredProcesses.isEmpty()) {
                        item(key = "processes_no_matches") {
                            Text(
                                text = stringResource(R.string.companion_no_search_results),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(items = filteredProcesses) { process ->
                            ProcessRow(process)
                        }
                    }
                }
                selectedJob?.let { selected ->
                    item(key = "selected") {
                        SelectedJobCard(
                            job = selected,
                            deviceName = deviceLabel,
                            canAct = !state.loading,
                            onCancel = { controller.cancelJob(selected.id) },
                            onDelete = { controller.deleteJob(selected.id) },
                        )
                    }
                }
                item(key = "jobs_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag(CompanionTestTags.JobsSection),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.companion_jobs_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = stringResource(R.string.companion_jobs_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.polling) {
                            MeshLoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                size = 18.dp,
                            )
                        }
                        TextButton(
                            onClick = { clearHistoryConfirmation = true },
                            enabled = state.available &&
                                !state.loading &&
                                state.jobs.any { it.terminal },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(CompanionTestTags.ClearHistory),
                        ) {
                            Text(stringResource(R.string.companion_jobs_clear))
                        }
                    }
                }
                if (state.jobs.isEmpty()) {
                    item(key = "jobs_empty") {
                        Text(
                            text = stringResource(R.string.companion_jobs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(items = state.jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            selected = selectedJob?.id == job.id,
                            onClick = { controller.selectJob(job.id) },
                        )
                    }
                }
                item(key = "events") {
                    EventsCard(events = state.events)
                }
                item(key = "help") { CompanionHelpCard() }
            }
        }
    }

    disclosureTemplate?.let { template ->
        DisclosureDialog(
            template = template,
            onDismiss = { disclosureTemplate = null },
            onConfirm = {
                disclosureTemplate = null
                controller.startRead(template)
            },
        )
    }
    serviceTarget?.let { target ->
        ServiceConfirmationDialog(
            target = target,
            deviceName = deviceLabel,
            onDismiss = { serviceTarget = null },
            onConfirm = {
                serviceTarget = null
                controller.serviceAction(target.action, target.service.name)
            },
        )
    }
    if (clearHistoryConfirmation) {
        ClearHistoryDialog(
            onDismiss = { clearHistoryConfirmation = false },
            onConfirm = {
                clearHistoryConfirmation = false
                controller.clearHistory()
            },
        )
    }
}

@Composable
private fun CompanionHeader(
    deviceName: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.xs, end = spacing.sm, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag(CompanionTestTags.Back),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.companion_back),
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_help),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = deviceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.size(48.dp).testTag(CompanionTestTags.Refresh),
        ) {
            if (loading) {
                MeshLoadingIndicator(modifier = Modifier.size(18.dp), size = 18.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.companion_refresh),
                )
            }
        }
    }
}

@Composable
private fun CompanionIntroCard() {
    val spacing = MeshTheme.spacing
    MeshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.companion_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.companion_intro_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompanionChecksCard(
    diagnosticsJob: CompanionJob?,
    servicesJob: CompanionJob?,
    processesJob: CompanionJob?,
    canStart: Boolean,
    hasActiveJob: Boolean,
    hasPendingOperation: Boolean,
    onRun: (CompanionTemplate) -> Unit,
) {
    val spacing = MeshTheme.spacing
    MeshCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SectionHeader(
                title = stringResource(R.string.companion_checks_title),
                subtitle = stringResource(R.string.companion_checks_subtitle),
            )
            CheckRow(
                icon = Icons.Outlined.Monitor,
                title = stringResource(R.string.companion_template_diagnostics),
                description = stringResource(R.string.companion_template_diagnostics_desc),
                detail = jobDetail(diagnosticsJob),
                actionLabel = if (diagnosticsJob == null) {
                    stringResource(R.string.companion_run_check)
                } else {
                    stringResource(R.string.companion_run_again)
                },
                enabled = canStart,
                tag = CompanionTestTags.RunDiagnostics,
                onClick = { onRun(CompanionTemplate.DIAGNOSTICS) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            CheckRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.companion_template_services),
                description = stringResource(R.string.companion_template_services_desc),
                detail = jobDetail(servicesJob),
                actionLabel = if (servicesJob == null) {
                    stringResource(R.string.companion_read)
                } else {
                    stringResource(R.string.companion_run_again)
                },
                enabled = canStart,
                tag = CompanionTestTags.RunServices,
                onClick = { onRun(CompanionTemplate.SERVICES) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            CheckRow(
                icon = Icons.Outlined.Memory,
                title = stringResource(R.string.companion_template_processes),
                description = stringResource(R.string.companion_template_processes_desc),
                detail = jobDetail(processesJob),
                actionLabel = if (processesJob == null) {
                    stringResource(R.string.companion_read)
                } else {
                    stringResource(R.string.companion_run_again)
                },
                enabled = canStart,
                tag = CompanionTestTags.RunProcesses,
                onClick = { onRun(CompanionTemplate.PROCESSES) },
            )
            if (hasActiveJob) {
                Text(
                    text = stringResource(R.string.companion_active_job_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (hasPendingOperation) {
                Text(
                    text = stringResource(R.string.companion_pending_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(
    icon: ImageVector,
    title: String,
    description: String,
    detail: String,
    actionLabel: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    Column(Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(icon = icon, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = detail,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun jobDetail(job: CompanionJob?): String = if (job == null) {
    stringResource(R.string.companion_not_run)
} else {
    stringResource(
        R.string.companion_last_run,
        "${formatTime(job.createdAt)} · ${job.status.label()}",
    )
}

@Composable
private fun DiagnosticsCard(
    job: CompanionJob,
    diagnostics: CompanionDiagnostics?,
    canRun: Boolean,
    shareEnabled: Boolean,
    onRunAgain: () -> Unit,
    onShare: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    val unknown = stringResource(R.string.companion_unknown)
    val memoryText = if (diagnostics != null) memorySummary(diagnostics.memory) else null
    MeshCard(Modifier.fillMaxWidth().testTag(CompanionTestTags.DiagnosticsSection)) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.companion_diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = job.status.label(), color = statusColor(job.status))
            }
            if (diagnostics == null) {
                Text(
                    text = stringResource(R.string.companion_findings_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DetailRow(
                    label = stringResource(R.string.companion_diagnostics_os),
                    value = diagnostics.osDescription ?: diagnostics.os ?: unknown,
                )
                DetailRow(
                    label = stringResource(R.string.companion_diagnostics_uptime),
                    value = durationText(diagnostics.uptimeSeconds),
                )
                DetailRow(
                    label = stringResource(R.string.companion_diagnostics_cpu),
                    value = cpuText(diagnostics.cpu),
                )
                DetailRow(
                    label = stringResource(R.string.companion_diagnostics_memory),
                    value = memoryText ?: unknown,
                )
                Text(
                    text = stringResource(R.string.companion_diagnostics_disks),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (diagnostics.disks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.companion_not_reported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (disk in diagnostics.disks.take(MAX_DISKS_SHOWN)) {
                        Text(
                            text = diskLine(disk),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (diagnostics.disks.size > MAX_DISKS_SHOWN) {
                        Text(
                            text = stringResource(R.string.companion_diagnostics_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.companion_diagnostics_network,
                        diagnostics.network.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FindingsSection(CompanionFindings.evaluate(diagnostics))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onRunAgain,
                    enabled = canRun,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.companion_run_again))
                }
                Button(
                    onClick = onShare,
                    enabled = shareEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(CompanionTestTags.ShareReport),
                ) {
                    Text(stringResource(R.string.companion_report_share))
                }
            }
            if (!shareEnabled) {
                Text(
                    text = stringResource(R.string.companion_report_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FindingsSection(assessment: CompanionAssessment) {
    val spacing = MeshTheme.spacing
    val unknown = stringResource(R.string.companion_unknown)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(CompanionTestTags.Findings),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.companion_findings_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!assessment.hasConcern && assessment.diskMeasured && assessment.memoryMeasured) {
            Text(
                text = stringResource(R.string.companion_findings_no_high),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (disk in assessment.diskHigh) {
            Text(
                text = stringResource(
                    R.string.companion_findings_disk_high,
                    disk.mount ?: unknown,
                    CompanionFindings.percentText(disk.percent),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MeshTheme.colors.warning,
            )
        }
        assessment.memoryHighPercent?.let { percent ->
            Text(
                text = stringResource(
                    R.string.companion_findings_memory_high,
                    CompanionFindings.percentText(percent),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MeshTheme.colors.warning,
            )
        }
        if (!assessment.diskMeasured) {
            Text(
                text = stringResource(R.string.companion_findings_disk_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!assessment.memoryMeasured) {
            Text(
                text = stringResource(R.string.companion_findings_memory_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (assessment.truncated) {
            Text(
                text = stringResource(R.string.companion_findings_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServiceRow(
    service: CompanionServiceItem,
    canStart: Boolean,
    onAction: (CompanionServiceAction) -> Unit,
) {
    val spacing = MeshTheme.spacing
    var menuExpanded by remember(service.name) { mutableStateOf(false) }
    val displayName = service.displayName ?: service.name
    val actionsLabel = stringResource(R.string.companion_service_actions, displayName)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CompanionTestTags.serviceRow(service.name))
                .padding(vertical = spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            IconBadge(icon = Icons.Outlined.Settings, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = displayName, style = MaterialTheme.typography.titleSmall)
                if (displayName != service.name) {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                service.state?.let {
                    Text(
                        text = stringResource(R.string.companion_service_state, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                service.startMode?.let {
                    Text(
                        text = stringResource(R.string.companion_service_start_mode, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = canStart,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(CompanionTestTags.serviceMenu(service.name)),
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
                    CompanionServiceAction.entries.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label()) },
                            leadingIcon = { Icon(action.icon(), contentDescription = null) },
                            enabled = canStart,
                            onClick = {
                                menuExpanded = false
                                onAction(action)
                            },
                            modifier = Modifier
                                .testTag(CompanionTestTags.serviceAction(service.name, action)),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun ProcessRow(process: CompanionProcessItem) {
    val spacing = MeshTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = process.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.companion_process_pid, process.pid),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        process.cpu?.let {
            Text(
                text = stringResource(R.string.companion_process_cpu, decimalText(it)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        process.memoryBytes?.let {
            Text(
                text = stringResource(R.string.companion_process_memory, bytesText(it)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedJobCard(
    job: CompanionJob,
    deviceName: String,
    canAct: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    val unknown = stringResource(R.string.companion_unknown)
    MeshCard(Modifier.fillMaxWidth().testTag(CompanionTestTags.SelectedJob)) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.companion_result_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = job.status.label(), color = statusColor(job.status))
            }
            DetailRow(
                label = stringResource(R.string.companion_result_template),
                value = job.template?.label() ?: unknown,
            )
            DetailRow(
                label = stringResource(R.string.companion_result_created),
                value = formatTime(job.createdAt),
            )
            val finishedAt = job.finishedAt
            if (finishedAt != null) {
                DetailRow(
                    label = stringResource(R.string.companion_result_finished),
                    value = formatTime(finishedAt),
                )
            }
            DetailRow(
                label = stringResource(R.string.companion_result_exit),
                value = job.exitCode?.toString() ?: unknown,
            )
            if (job.terminal) {
                Text(
                    text = if (job.verifiedComplete) {
                        stringResource(R.string.companion_result_verified)
                    } else {
                        stringResource(R.string.companion_result_not_verified)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.verifiedComplete) {
                        MeshTheme.colors.online
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (job.resultParseFailed) {
                Text(
                    text = stringResource(R.string.companion_result_parse_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshTheme.colors.danger,
                )
            }
            val serviceResult = job.result as? CompanionServiceMutation
            if (serviceResult != null && job.verifiedComplete) {
                Text(
                    text = stringResource(R.string.companion_service_result_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                val state = serviceResult.state ?: unknown
                Text(
                    text = if (serviceResult.changed) {
                        stringResource(
                            R.string.companion_service_result_changed,
                            serviceResult.name,
                            deviceName,
                            state,
                        )
                    } else {
                        stringResource(
                            R.string.companion_service_result_unchanged,
                            serviceResult.name,
                            deviceName,
                            state,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            job.errorCode?.let { errorCode ->
                Text(
                    text = errorCode.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshTheme.colors.danger,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = stringResource(R.string.companion_result_output_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                job.status == CompanionJobStatus.RUNNING -> {
                    Text(
                        text = stringResource(R.string.companion_result_cancel_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshTheme.colors.danger,
                    )
                }
                job.status == CompanionJobStatus.QUEUED -> {
                    TextButton(
                        onClick = onCancel,
                        enabled = canAct,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(CompanionTestTags.CancelJob),
                    ) {
                        Text(stringResource(R.string.companion_result_cancel_queued))
                    }
                }
                job.terminal -> {
                    TextButton(
                        onClick = onDelete,
                        enabled = canAct,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(CompanionTestTags.DeleteJob),
                    ) {
                        Text(stringResource(R.string.companion_result_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(
    job: CompanionJob,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(CompanionTestTags.job(job.id))
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = job.status.icon(), size = 36.dp)
        Column(
            modifier = Modifier.weight(1f).padding(start = spacing.md),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = job.template?.label() ?: stringResource(R.string.companion_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatTime(job.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) {
                Text(
                    text = stringResource(R.string.companion_job_selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        StatusPill(text = job.status.label(), color = statusColor(job.status))
    }
}

@Composable
private fun EventsCard(events: List<CompanionEvent>) {
    val spacing = MeshTheme.spacing
    MeshCard(Modifier.fillMaxWidth().testTag(CompanionTestTags.EventsSection)) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.companion_events_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.companion_events_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (event in events.takeLast(MAX_EVENTS_SHOWN).asReversed()) {
                    Text(
                        text = "${event.kind.label()} · ${formatTime(event.time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanionHelpCard() {
    val spacing = MeshTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    val steps = listOf(
        stringResource(R.string.companion_help_step_1),
        stringResource(R.string.companion_help_step_2),
        stringResource(R.string.companion_help_step_3),
        stringResource(R.string.companion_help_step_4),
        stringResource(R.string.companion_help_step_5),
    )
    MeshCard(Modifier.fillMaxWidth().testTag(CompanionTestTags.QuickHelp)) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.companion_help_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = spacing.xs),
                )
                Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) Text(
                text = stringResource(R.string.companion_help_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (step in if (expanded) steps else emptyList()) {
                Text(text = step, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UnavailableCard() {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.companion_unavailable),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.companion_unavailable_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CompanionErrorCard(code: CompanionErrorCode, onDismiss: () -> Unit) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CompanionTestTags.ErrorCard)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.companion_error_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(text = code.message(), style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.companion_pending_discard))
                }
            }
        }
    }
}

@Composable
private fun PendingOperationCard(
    template: CompanionTemplate,
    code: CompanionErrorCode?,
    onRecheck: () -> Unit,
    onDiscard: () -> Unit,
) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CompanionTestTags.PendingCard)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.companion_uncertain_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.companion_uncertain_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.companion_pending_template, template.label()),
                style = MaterialTheme.typography.bodySmall,
            )
            if (code != null) {
                Text(text = code.message(), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                TextButton(
                    onClick = onRecheck,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(CompanionTestTags.PendingRecheck),
                ) {
                    Text(stringResource(R.string.companion_pending_recheck))
                }
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag(CompanionTestTags.PendingDiscard),
                ) {
                    Text(stringResource(R.string.companion_pending_discard))
                }
            }
        }
    }
}

@Composable
private fun DisclosureDialog(
    template: CompanionTemplate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val body = when (template) {
        CompanionTemplate.DIAGNOSTICS -> stringResource(R.string.companion_disclosure_diagnostics)
        CompanionTemplate.SERVICES -> stringResource(R.string.companion_disclosure_services)
        CompanionTemplate.PROCESSES -> stringResource(R.string.companion_disclosure_processes)
        else -> stringResource(R.string.companion_disclosure_diagnostics)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CompanionTestTags.DisclosureDialog),
        title = { Text(stringResource(R.string.companion_disclosure_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm),
            ) {
                Text(text = body)
                Text(
                    text = stringResource(R.string.companion_disclosure_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.DisclosureConfirm),
            ) {
                Text(stringResource(R.string.companion_disclosure_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.DisclosureCancel),
            ) {
                Text(stringResource(R.string.companion_disclosure_cancel))
            }
        },
    )
}

@Composable
private fun ServiceConfirmationDialog(
    target: ServiceTarget,
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val serviceLabel = target.service.displayName ?: target.service.name
    val state = target.service.state ?: stringResource(R.string.companion_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CompanionTestTags.ServiceConfirmDialog),
        title = {
            Text(
                stringResource(
                    R.string.companion_service_confirm_title,
                    target.action.label(),
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm),
            ) {
                Text(
                    stringResource(
                        R.string.companion_service_confirm_body,
                        serviceLabel,
                        deviceName,
                        state,
                    ),
                )
                Text(
                    text = stringResource(R.string.companion_service_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.ServiceConfirm),
            ) {
                Text(target.action.label())
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.ServiceCancel),
            ) {
                Text(stringResource(R.string.companion_service_confirm_cancel))
            }
        },
    )
}

@Composable
private fun ClearHistoryDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CompanionTestTags.ClearHistoryDialog),
        title = { Text(stringResource(R.string.companion_jobs_clear_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.companion_jobs_clear_confirm_body),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.ClearHistoryConfirm),
            ) {
                Text(stringResource(R.string.companion_jobs_clear_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(CompanionTestTags.ClearHistoryCancel),
            ) {
                Text(stringResource(R.string.companion_jobs_clear_cancel))
            }
        },
    )
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
private fun diskLine(disk: CompanionDisk): String {
    val unknown = stringResource(R.string.companion_unknown)
    val label = disk.mount ?: disk.device ?: unknown
    val percent = disk.usedPercent?.let(CompanionFindings::percentText) ?: unknown
    return stringResource(
        R.string.companion_diagnostics_disk_line,
        label,
        percent,
        bytesText(disk.availableBytes),
        bytesText(disk.totalBytes),
    )
}

@Composable
private fun memorySummary(memory: CompanionMemory): String {
    val unknown = stringResource(R.string.companion_unknown)
    val used = memory.usedPercent?.let(CompanionFindings::percentText) ?: unknown
    return stringResource(
        R.string.companion_diagnostics_memory_line,
        used,
        bytesText(memory.availableBytes),
        bytesText(memory.totalBytes),
    )
}

@Composable
private fun cpuText(cpu: CompanionCpu): String {
    val unknown = stringResource(R.string.companion_unknown)
    val logical = cpu.logicalCount?.toString() ?: unknown
    val load = when {
        cpu.loadPercent != null -> stringResource(
            R.string.companion_cpu_load_percent,
            CompanionFindings.percentText(cpu.loadPercent),
        )
        cpu.load1 != null -> stringResource(R.string.companion_cpu_load1, decimalText(cpu.load1))
        else -> unknown
    }
    return stringResource(R.string.companion_diagnostics_cpu_line, logical, load)
}

@Composable
private fun durationText(seconds: Long?): String = if (seconds == null) {
    stringResource(R.string.companion_unknown)
} else if (seconds < 60L) {
    stringResource(R.string.companion_duration_seconds, seconds)
} else {
    val days = seconds / 86_400L
    val hours = (seconds % 86_400L) / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val parts = mutableListOf<String>()
    if (days > 0L) parts += stringResource(R.string.companion_duration_days, days)
    if (hours > 0L) parts += stringResource(R.string.companion_duration_hours, hours)
    if (minutes > 0L) parts += stringResource(R.string.companion_duration_minutes, minutes)
    if (parts.isEmpty()) parts += stringResource(R.string.companion_duration_seconds, seconds)
    parts.take(2).joinToString(" ")
}

@Composable
private fun bytesText(value: Long?): String {
    val context = LocalContext.current
    return if (value == null) {
        stringResource(R.string.companion_unknown)
    } else {
        remember(value, context) { Formatter.formatShortFileSize(context, value) }
    }
}

private fun decimalText(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)

@Composable
private fun companionReportLines(report: CompanionReport): CompanionReportLines {
    val unknown = stringResource(R.string.companion_report_unknown)
    val warningLines = ArrayList<String>()
    for (warning in report.warnings) {
        val line = when (warning.kind) {
            CompanionReportWarningKind.DISK_HIGH -> stringResource(
                R.string.companion_report_disk_warning,
                warning.percent?.let(CompanionFindings::percentText) ?: unknown,
            )
            CompanionReportWarningKind.MEMORY_HIGH -> stringResource(
                R.string.companion_report_memory_warning,
                warning.percent?.let(CompanionFindings::percentText) ?: unknown,
            )
            CompanionReportWarningKind.DISK_UNKNOWN ->
                stringResource(R.string.companion_report_disk_unknown)
            CompanionReportWarningKind.MEMORY_UNKNOWN ->
                stringResource(R.string.companion_report_memory_unknown)
            CompanionReportWarningKind.TRUNCATED ->
                stringResource(R.string.companion_report_truncated)
            CompanionReportWarningKind.INCOMPLETE ->
                stringResource(R.string.companion_report_incomplete)
            CompanionReportWarningKind.NO_RESULT ->
                stringResource(R.string.companion_report_no_result)
            CompanionReportWarningKind.RESULT_UNREADABLE ->
                stringResource(R.string.companion_report_result_unreadable)
        }
        warningLines += line
    }
    return CompanionReportLines(
        title = stringResource(R.string.companion_report_title),
        template = stringResource(
            R.string.companion_report_template,
            CompanionTemplate.fromId(report.templateId)?.label() ?: unknown,
        ),
        created = stringResource(
            R.string.companion_report_created,
            formatReportTime(report.createdAt),
        ),
        finished = stringResource(
            R.string.companion_report_finished,
            report.finishedAt?.let(::formatReportTime) ?: unknown,
        ),
        status = stringResource(R.string.companion_report_status, report.status.label()),
        exitCode = stringResource(
            R.string.companion_report_exit,
            report.exitCode?.toString() ?: unknown,
        ),
        diskCount = stringResource(
            R.string.companion_report_disks,
            report.diskCount?.toString() ?: unknown,
        ),
        networkCount = stringResource(
            R.string.companion_report_network,
            report.networkCount?.toString() ?: unknown,
        ),
        warningsHeading = stringResource(R.string.companion_report_warnings),
        noWarnings = stringResource(R.string.companion_report_no_warnings),
        warningLines = warningLines,
        disclaimer = stringResource(R.string.companion_report_disclaimer),
    )
}

private fun filterServices(
    services: List<CompanionServiceItem>,
    query: String,
): List<CompanionServiceItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return services
    return services.filter { service ->
        service.name.contains(needle, ignoreCase = true) ||
            service.displayName?.contains(needle, ignoreCase = true) == true ||
            service.state?.contains(needle, ignoreCase = true) == true ||
            service.startMode?.contains(needle, ignoreCase = true) == true
    }
}

private fun filterProcesses(
    processes: List<CompanionProcessItem>,
    query: String,
): List<CompanionProcessItem> {
    val needle = query.trim()
    if (needle.isEmpty()) return processes
    return processes.filter { process ->
        process.name.contains(needle, ignoreCase = true) ||
            process.pid.toString().contains(needle, ignoreCase = true)
    }
}

private fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatReportTime(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

@Composable
private fun CompanionJobStatus.label(): String = stringResource(
    when (this) {
        CompanionJobStatus.QUEUED -> R.string.companion_status_queued
        CompanionJobStatus.RUNNING -> R.string.companion_status_running
        CompanionJobStatus.COMPLETED -> R.string.companion_status_completed
        CompanionJobStatus.FAILED -> R.string.companion_status_failed
        CompanionJobStatus.EXPIRED -> R.string.companion_status_expired
        CompanionJobStatus.CANCELLED -> R.string.companion_status_cancelled
        CompanionJobStatus.UNKNOWN -> R.string.companion_status_unknown
    },
)

@Composable
private fun CompanionTemplate.label(): String = stringResource(
    when (this) {
        CompanionTemplate.DIAGNOSTICS -> R.string.companion_template_diagnostics
        CompanionTemplate.SERVICES -> R.string.companion_template_services
        CompanionTemplate.PROCESSES -> R.string.companion_template_processes
        CompanionTemplate.SERVICE_START,
        CompanionTemplate.SERVICE_STOP,
        CompanionTemplate.SERVICE_RESTART,
        -> R.string.companion_template_services
    },
)

@Composable
private fun CompanionServiceAction.label(): String = stringResource(
    when (this) {
        CompanionServiceAction.START -> R.string.companion_action_start
        CompanionServiceAction.STOP -> R.string.companion_action_stop
        CompanionServiceAction.RESTART -> R.string.companion_action_restart
    },
)

private fun CompanionServiceAction.icon(): ImageVector = when (this) {
    CompanionServiceAction.START -> Icons.Outlined.PlayArrow
    CompanionServiceAction.STOP -> Icons.Outlined.Stop
    CompanionServiceAction.RESTART -> Icons.Outlined.RestartAlt
}

private fun CompanionJobStatus.icon(): ImageVector = when (this) {
    CompanionJobStatus.QUEUED,
    CompanionJobStatus.RUNNING,
    -> Icons.Outlined.Schedule
    CompanionJobStatus.COMPLETED -> Icons.Outlined.CheckCircle
    CompanionJobStatus.FAILED,
    CompanionJobStatus.EXPIRED,
    -> Icons.Outlined.ErrorOutline
    CompanionJobStatus.CANCELLED,
    CompanionJobStatus.UNKNOWN,
    -> Icons.Outlined.Warning
}

@Composable
private fun statusColor(status: CompanionJobStatus): Color = when (status) {
    CompanionJobStatus.QUEUED,
    CompanionJobStatus.RUNNING,
    -> MaterialTheme.colorScheme.primary
    CompanionJobStatus.COMPLETED -> MeshTheme.colors.online
    CompanionJobStatus.FAILED,
    CompanionJobStatus.EXPIRED,
    -> MeshTheme.colors.danger
    CompanionJobStatus.CANCELLED,
    CompanionJobStatus.UNKNOWN,
    -> MeshTheme.colors.warning
}

@Composable
private fun CompanionEventKind.label(): String = stringResource(
    when (this) {
        CompanionEventKind.JOB_COMPLETED -> R.string.companion_event_job_completed
        CompanionEventKind.JOB_FAILED -> R.string.companion_event_job_failed
        CompanionEventKind.DEVICE_ONLINE -> R.string.companion_event_device_online
        CompanionEventKind.DEVICE_OFFLINE -> R.string.companion_event_device_offline
        CompanionEventKind.DEVICE_REQUEST_HELP -> R.string.companion_event_device_request_help
        CompanionEventKind.UNKNOWN -> R.string.companion_event_unknown
    },
)

@Composable
private fun CompanionErrorCode.message(): String = stringResource(
    when (this) {
        CompanionErrorCode.INVALID_REQUEST_ID -> R.string.companion_error_invalid_request_id
        CompanionErrorCode.UNKNOWN_OP -> R.string.companion_error_unknown_op
        CompanionErrorCode.UNAUTHENTICATED -> R.string.companion_error_unauthenticated
        CompanionErrorCode.FORBIDDEN -> R.string.companion_error_forbidden
        CompanionErrorCode.INVALID_ARGUMENT -> R.string.companion_error_invalid_argument
        CompanionErrorCode.UNSUPPORTED_OS -> R.string.companion_error_unsupported_os
        CompanionErrorCode.CONFIRMATION_REQUIRED -> R.string.companion_error_confirmation_required
        CompanionErrorCode.AGENT_OFFLINE -> R.string.companion_error_agent_offline
        CompanionErrorCode.OPERATION_CONFLICT -> R.string.companion_error_operation_conflict
        CompanionErrorCode.STALE_OPERATION -> R.string.companion_error_stale_operation
        CompanionErrorCode.QUOTA_EXCEEDED -> R.string.companion_error_quota_exceeded
        CompanionErrorCode.NODE_BUSY -> R.string.companion_error_node_busy
        CompanionErrorCode.JOB_NOT_FOUND -> R.string.companion_error_job_not_found
        CompanionErrorCode.CANCEL_UNSUPPORTED -> R.string.companion_error_cancel_unsupported
        CompanionErrorCode.INTERNAL_ERROR -> R.string.companion_error_internal
        CompanionErrorCode.NOT_AUTHENTICATED -> R.string.companion_error_not_authenticated
        CompanionErrorCode.DISCONNECTED -> R.string.companion_error_disconnected
        CompanionErrorCode.SEND_FAILED -> R.string.companion_error_send_failed
        CompanionErrorCode.TOO_MANY_REQUESTS -> R.string.companion_error_too_many_requests
        CompanionErrorCode.TIMEOUT -> R.string.companion_error_timeout
        CompanionErrorCode.NETWORK -> R.string.companion_error_network
        CompanionErrorCode.PROTOCOL -> R.string.companion_error_protocol
        CompanionErrorCode.UNSUPPORTED_PROTOCOL -> R.string.companion_error_unsupported_protocol
        CompanionErrorCode.JOB_ACTIVE -> R.string.companion_error_job_active
        CompanionErrorCode.PENDING_OPERATION -> R.string.companion_error_pending_operation
        CompanionErrorCode.UNKNOWN -> R.string.companion_error_unknown
    },
)

@Composable
private fun CompanionJobErrorCode.message(): String = stringResource(
    when (this) {
        CompanionJobErrorCode.MARKER_MISSING -> R.string.companion_job_error_marker_missing
        CompanionJobErrorCode.COMMAND_FAILED -> R.string.companion_job_error_command_failed
        CompanionJobErrorCode.INVALID_RESULT -> R.string.companion_job_error_invalid_result
        CompanionJobErrorCode.DISPATCH_FAILED -> R.string.companion_job_error_dispatch_failed
        CompanionJobErrorCode.TIMEOUT -> R.string.companion_job_error_timeout
        CompanionJobErrorCode.SERVER_RESTART -> R.string.companion_job_error_server_restart
        CompanionJobErrorCode.AGENT_WAIT_TIMEOUT -> R.string.companion_job_error_agent_wait_timeout
        CompanionJobErrorCode.UNSUPPORTED_OS -> R.string.companion_job_error_unsupported_os
        CompanionJobErrorCode.UNKNOWN -> R.string.companion_job_error_unknown
    },
)

private const val MAX_DISKS_SHOWN = 20
private const val MAX_EVENTS_SHOWN = 10
