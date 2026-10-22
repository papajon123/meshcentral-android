package ru.papajon.meshcentralmobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionFindingsTest {
    @Test
    fun thresholdsAreDeterministicAndUnknownStaysUnknown() {
        val diagnostics = diagnostics(
            disks = listOf(
                CompanionDisk("/dev/sda1", "/", 1000L, 100L, 90.0),
                CompanionDisk("/dev/sdb1", "/data", 1000L, 200L, 89.9),
                CompanionDisk("/dev/sdc1", "/other", null, null, null),
            ),
            memoryPercent = 95.0,
        )
        val assessment = CompanionFindings.evaluate(diagnostics)
        assertEquals(1, assessment.diskHigh.size)
        assertEquals("/", assessment.diskHigh.single().mount)
        assertEquals(95.0, assessment.memoryHighPercent!!, 0.0)
        assertTrue(assessment.diskMeasured)
        assertTrue(assessment.memoryMeasured)
        assertTrue(assessment.hasConcern)
        assertFalse(assessment.hasUnknown)
    }

    @Test
    fun missingMeasurementsAreReportedAsUnknown() {
        val assessment = CompanionFindings.evaluate(
            diagnostics(
                disks = emptyList(),
                memoryPercent = null,
            ),
        )
        assertTrue(assessment.diskHigh.isEmpty())
        assertNull(assessment.memoryHighPercent)
        assertFalse(assessment.diskMeasured)
        assertFalse(assessment.memoryMeasured)
        assertFalse(assessment.hasConcern)
        assertTrue(assessment.hasUnknown)
    }

    @Test
    fun reportContainsOnlyAllowedAnonymizedFields() {
        val job = CompanionJob(
            id = "mc_secret_id",
            nodeId = "node/domain/host-secret",
            templateId = "diagnostics",
            template = CompanionTemplate.DIAGNOSTICS,
            parameters = emptyMap(),
            createdAt = 1_700_000_000_000L,
            startedAt = 1_700_000_000_100L,
            finishedAt = 1_700_000_000_900L,
            status = CompanionJobStatus.COMPLETED,
            exitCode = 0,
            result = diagnostics(
                disks = listOf(CompanionDisk("/dev/sda1", "/", 1000L, 100L, 91.0)),
                memoryPercent = 96.0,
                hostname = "host-secret",
                address = "10.0.0.1/24",
                mac = "AA:BB:CC:DD:EE:FF",
            ),
            resultParseFailed = false,
            truncated = false,
            errorCode = null,
        )
        val report = CompanionReport.from(job)!!
        val text = report.toShareText(
            CompanionReportLines(
                title = "Title",
                template = "Template: ${report.templateId}",
                created = "Created: ${report.createdAt}",
                finished = "Finished: ${report.finishedAt}",
                status = "Status: ${report.status.wire}",
                exitCode = "Exit code: ${report.exitCode}",
                diskCount = "Disk entries: ${report.diskCount}",
                networkCount = "Network interfaces: ${report.networkCount}",
                warningsHeading = "Warnings:",
                noWarnings = "Warnings: none",
                warningLines = report.warnings.map { it.kind.name },
                disclaimer = "Anonymized report.",
            ),
        )
        assertTrue(text.contains("Template: diagnostics"))
        assertTrue(text.contains("Status: completed"))
        assertTrue(text.contains("DISK_HIGH"))
        assertTrue(text.contains("MEMORY_HIGH"))
        assertFalse(text.contains("mc_secret_id"))
        assertFalse(text.contains("host-secret"))
        assertFalse(text.contains("10.0.0.1"))
        assertFalse(text.contains("AA:BB"))
        assertFalse(text.contains("node/domain"))
        assertFalse(text.contains("TOP SECRET"))
    }

    @Test
    fun incompleteReportSaysTheJobIsNotVerified() {
        val job = CompanionJob(
            id = "mc_1",
            nodeId = "node/domain/device",
            templateId = "diagnostics",
            template = CompanionTemplate.DIAGNOSTICS,
            parameters = emptyMap(),
            createdAt = 1L,
            startedAt = null,
            finishedAt = null,
            status = CompanionJobStatus.UNKNOWN,
            exitCode = null,
            result = null,
            resultParseFailed = false,
            truncated = false,
            errorCode = CompanionJobErrorCode.MARKER_MISSING,
        )
        val report = CompanionReport.from(job)!!
        assertTrue(report.warnings.any { it.kind == CompanionReportWarningKind.NO_RESULT })
        assertTrue(report.warnings.any { it.kind == CompanionReportWarningKind.INCOMPLETE })
    }

    private fun diagnostics(
        disks: List<CompanionDisk>,
        memoryPercent: Double?,
        hostname: String? = "host",
        address: String? = null,
        mac: String? = null,
    ): CompanionDiagnostics = CompanionDiagnostics(
        os = "linux",
        hostname = hostname,
        osDescription = "Ubuntu",
        uptimeSeconds = 3600,
        cpu = CompanionCpu(logicalCount = 4, load1 = 0.5, loadPercent = null),
        memory = CompanionMemory(totalBytes = 1000L, availableBytes = 400L, usedPercent = memoryPercent),
        disks = disks,
        network = if (address != null || mac != null) {
            listOf(CompanionNetworkInterface("eth0", mac, listOfNotNull(address)))
        } else {
            emptyList()
        },
        truncated = false,
    )
}
