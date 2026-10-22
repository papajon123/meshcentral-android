package ru.papajon.meshcentralmobile.companion

/** Bounded, whitelisted capabilities returned by the plugin. */
data class CompanionCapabilities(
    val protocolVersion: Int,
    val schemaVersion: Int,
    val operations: Set<String>,
    val templates: List<CompanionTemplateInfo>,
    val platforms: Set<String>,
    val eventKinds: Set<CompanionEventKind>,
    val limits: CompanionLimits,
) {
    fun supports(template: CompanionTemplate): Boolean =
        operations.contains("startJob") && templates.any { it.id == template.id }
}

data class CompanionTemplateInfo(
    val id: String,
    val kind: String?,
    val parameters: Set<String>,
)

data class CompanionLimits(
    val maxJobs: Int?,
    val maxEvents: Int?,
    val maxOutputBytes: Int?,
    val historyDays: Int?,
    val requestIdMaxLength: Int?,
    val eventLimitMax: Int?,
    val diagnosticsAgentWaitSeconds: Int?,
    val executionTimeoutSeconds: Int?,
)

data class CompanionCpu(
    val logicalCount: Int?,
    val load1: Double?,
    val loadPercent: Double?,
)

data class CompanionMemory(
    val totalBytes: Long?,
    val availableBytes: Long?,
    val usedPercent: Double?,
)

data class CompanionDisk(
    val device: String?,
    val mount: String?,
    val totalBytes: Long?,
    val availableBytes: Long?,
    val usedPercent: Double?,
)

data class CompanionNetworkInterface(
    val name: String?,
    val mac: String?,
    val addresses: List<String>,
)

data class CompanionDiagnostics(
    val os: String?,
    val hostname: String?,
    val osDescription: String?,
    val uptimeSeconds: Long?,
    val cpu: CompanionCpu,
    val memory: CompanionMemory,
    val disks: List<CompanionDisk>,
    val network: List<CompanionNetworkInterface>,
    val truncated: Boolean,
) : CompanionResult

data class CompanionServiceItem(
    val name: String,
    val displayName: String?,
    val state: String?,
    val startMode: String?,
)

data class CompanionServices(
    val os: String?,
    val items: List<CompanionServiceItem>,
    val truncated: Boolean,
) : CompanionResult

data class CompanionProcessItem(
    val pid: Long,
    val name: String,
    val cpu: Double?,
    val memoryBytes: Long?,
)

data class CompanionProcesses(
    val os: String?,
    val items: List<CompanionProcessItem>,
    val truncated: Boolean,
) : CompanionResult

data class CompanionServiceMutation(
    val os: String?,
    val operation: String,
    val name: String,
    val changed: Boolean,
    val state: String?,
) : CompanionResult

sealed interface CompanionResult

data class CompanionJob(
    val id: String,
    val nodeId: String,
    val templateId: String,
    val template: CompanionTemplate?,
    val parameters: Map<String, String>,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val status: CompanionJobStatus,
    val exitCode: Int?,
    val result: CompanionResult?,
    val resultParseFailed: Boolean,
    val truncated: Boolean,
    val errorCode: CompanionJobErrorCode?,
) {
    val active: Boolean get() = status.active
    val terminal: Boolean get() = status.terminal

    /**
     * Completion is deliberately strict: `completed`, exit code 0 and a parsed structured
     * result. A completed status alone is never treated as success.
     */
    val verifiedComplete: Boolean
        get() = status == CompanionJobStatus.COMPLETED &&
            exitCode == 0 &&
            result != null &&
            !resultParseFailed
}

data class CompanionEvent(
    val id: String,
    val sequence: Long,
    val kind: CompanionEventKind,
    val nodeId: String?,
    val jobId: String?,
    val time: Long,
    val expiresAt: Long?,
)

data class CompanionEventsPage(
    val items: List<CompanionEvent>,
    val cursor: Long,
    val resetCursor: Boolean,
)

data class CompanionUiState(
    val available: Boolean = false,
    val capabilities: CompanionCapabilities? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val jobs: List<CompanionJob> = emptyList(),
    val selectedJob: CompanionJob? = null,
    val events: List<CompanionEvent> = emptyList(),
    val eventCursor: Long = 0L,
    val error: CompanionErrorCode? = null,
    /** True when a mutation may have reached the server but no answer was received. */
    val errorUncertain: Boolean = false,
    val pendingOperation: PendingOperation? = null,
)
