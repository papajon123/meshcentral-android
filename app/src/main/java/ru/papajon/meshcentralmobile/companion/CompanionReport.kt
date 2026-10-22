package ru.papajon.meshcentralmobile.companion

enum class CompanionReportWarningKind {
    DISK_HIGH,
    MEMORY_HIGH,
    DISK_UNKNOWN,
    MEMORY_UNKNOWN,
    TRUNCATED,
    INCOMPLETE,
    NO_RESULT,
    RESULT_UNREADABLE,
}

data class CompanionReportWarning(
    val kind: CompanionReportWarningKind,
    val percent: Double? = null,
)

/**
 * Anonymized report model. It intentionally cannot hold a device name, server, address, user,
 * job id, operation id, node id, hostname or raw output.
 */
data class CompanionReport(
    val templateId: String,
    val status: CompanionJobStatus,
    val exitCode: Int?,
    val createdAt: Long,
    val finishedAt: Long?,
    val diskCount: Int?,
    val networkCount: Int?,
    val warnings: List<CompanionReportWarning>,
) {
    fun toShareText(lines: CompanionReportLines): String = buildString {
        appendLine(lines.title)
        appendLine(lines.template)
        appendLine(lines.created)
        appendLine(lines.finished)
        appendLine(lines.status)
        appendLine(lines.exitCode)
        appendLine(lines.diskCount)
        appendLine(lines.networkCount)
        if (lines.warningLines.isEmpty()) {
            appendLine(lines.noWarnings)
        } else {
            appendLine(lines.warningsHeading)
            lines.warningLines.forEach { appendLine(it) }
        }
        appendLine(lines.disclaimer)
    }

    companion object {
        fun from(job: CompanionJob): CompanionReport? {
            if (job.template != CompanionTemplate.DIAGNOSTICS) return null
            val diagnostics = job.result as? CompanionDiagnostics
            val assessment = diagnostics?.let(CompanionFindings::evaluate)
            val warnings = buildList {
                if (diagnostics == null) {
                    add(CompanionReportWarning(CompanionReportWarningKind.NO_RESULT))
                } else {
                    assessment?.diskHigh?.forEach { pressure ->
                        add(
                            CompanionReportWarning(
                                kind = CompanionReportWarningKind.DISK_HIGH,
                                percent = pressure.percent,
                            ),
                        )
                    }
                    assessment?.memoryHighPercent?.let { percent ->
                        add(
                            CompanionReportWarning(
                                kind = CompanionReportWarningKind.MEMORY_HIGH,
                                percent = percent,
                            ),
                        )
                    }
                    if (assessment?.diskMeasured == false) {
                        add(CompanionReportWarning(CompanionReportWarningKind.DISK_UNKNOWN))
                    }
                    if (assessment?.memoryMeasured == false) {
                        add(CompanionReportWarning(CompanionReportWarningKind.MEMORY_UNKNOWN))
                    }
                    if (diagnostics.truncated || job.truncated) {
                        add(CompanionReportWarning(CompanionReportWarningKind.TRUNCATED))
                    }
                }
                if (job.resultParseFailed) {
                    add(CompanionReportWarning(CompanionReportWarningKind.RESULT_UNREADABLE))
                }
                if (!job.verifiedComplete) {
                    add(CompanionReportWarning(CompanionReportWarningKind.INCOMPLETE))
                }
            }
            return CompanionReport(
                templateId = job.templateId,
                status = job.status,
                exitCode = job.exitCode,
                createdAt = job.createdAt,
                finishedAt = job.finishedAt,
                diskCount = diagnostics?.disks?.size,
                networkCount = diagnostics?.network?.size,
                warnings = warnings,
            )
        }
    }
}

/** Fully resolved, localized lines. Every line is display text; no server message is accepted. */
data class CompanionReportLines(
    val title: String,
    val template: String,
    val created: String,
    val finished: String,
    val status: String,
    val exitCode: String,
    val diskCount: String,
    val networkCount: String,
    val warningsHeading: String,
    val noWarnings: String,
    val warningLines: List<String>,
    val disclaimer: String,
)
