package ru.papajon.meshcentralmobile.companion

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionParserTest {
    @Test
    fun diagnosticsAreBoundedTypedAndVerified() {
        val longMount = "m".repeat(600)
        val disks = JSONArray()
        for (index in 0 until 101) {
            disks.put(
                JSONObject()
                    .put("device", "/dev/sda$index")
                    .put("mount", if (index == 0) longMount else "/data$index")
                    .put("totalBytes", 1_000L)
                    .put("availableBytes", 100L)
                    .put("usedPercent", if (index == 0) 91.5 else 50),
            )
        }
        val network = JSONArray().put(
            JSONObject()
                .put("name", "eth0")
                .put("mac", "AA:BB:CC:DD:EE:FF")
                .put("addresses", JSONArray().put("192.168.1.10/24")),
        )
        val result = JSONObject()
            .put("os", "linux")
            .put("hostname", "host-1")
            .put("osDescription", "Ubuntu")
            .put("uptimeSeconds", 3600L)
            .put("cpu", JSONObject().put("logicalCount", 4).put("load1", 0.5))
            .put("memory", JSONObject().put("totalBytes", 1000L).put("availableBytes", 400L).put("usedPercent", 60))
            .put("disks", disks)
            .put("network", network)
            .put("truncated", false)
            .put("unknownServerField", "ignored")
        val job = parseJob(
            baseJob(
                status = "completed",
                exitCode = 0,
                result = result,
            ).put("output", "TOP SECRET RAW OUTPUT"),
        )

        assertTrue(job.verifiedComplete)
        assertEquals(CompanionJobStatus.COMPLETED, job.status)
        assertFalse(job.toString().contains("ignored"))
        assertFalse(job.toString().contains("TOP SECRET RAW OUTPUT"))
        val diagnostics = job.result as CompanionDiagnostics
        assertEquals(100, diagnostics.disks.size)
        assertTrue(diagnostics.truncated)
        assertEquals(512, diagnostics.disks.first().mount?.length)
        assertEquals(1, diagnostics.network.size)
        assertEquals(91.5, diagnostics.disks.first().usedPercent!!, 0.0)
    }

    @Test
    fun completionNeedsExitZeroAndAParsedResult() {
        val validResult = JSONObject()
            .put("os", "linux")
            .put("memory", JSONObject().put("totalBytes", 1000L).put("usedPercent", 50))
        assertTrue(parseJob(baseJob("completed", 0, validResult)).verifiedComplete)

        assertFalse(parseJob(baseJob("completed", 1, validResult)).verifiedComplete)
        assertFalse(parseJob(baseJob("completed", null, validResult)).verifiedComplete)
        assertFalse(parseJob(baseJob("completed", 0, null)).verifiedComplete)

        val malformed = parseJob(baseJob("completed", 0, JSONObject().put("unexpected", true)))
        assertTrue(malformed.resultParseFailed)
        assertNull(malformed.result)
        assertFalse(malformed.verifiedComplete)
    }

    @Test
    fun failedUnknownAndCancelledStayDistinct() {
        val failed = parseJob(
            baseJob("failed", 2, null).put("errorCode", "COMMAND_FAILED"),
        )
        assertEquals(CompanionJobStatus.FAILED, failed.status)
        assertEquals(CompanionJobErrorCode.COMMAND_FAILED, failed.errorCode)
        assertFalse(failed.verifiedComplete)

        val unknown = parseJob(
            baseJob("unknown", null, null).put("errorCode", "MARKER_MISSING"),
        )
        assertEquals(CompanionJobStatus.UNKNOWN, unknown.status)
        assertEquals(CompanionJobErrorCode.MARKER_MISSING, unknown.errorCode)

        val futureStatus = parseJob(baseJob("new-status-from-server", null, null))
        assertEquals(CompanionJobStatus.UNKNOWN, futureStatus.status)
    }

    @Test
    fun servicesAndProcessesAreWhitelisted() {
        val servicesResult = JSONObject()
            .put("os", "windows")
            .put("truncated", false)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("name", "Spooler")
                        .put("displayName", "Print Spooler")
                        .put("state", "Running")
                        .put("startMode", "Automatic")
                        .put("servicePath", "C:\\secret\\spooler.exe")
                        .put("arguments", "--secret"),
                ),
            )
        val services = parseJob(
            baseJob("completed", 0, servicesResult, templateId = "services"),
        ).result as CompanionServices
        assertEquals(1, services.items.size)
        assertFalse(services.toString().contains("servicePath"))
        assertFalse(services.toString().contains("C:\\secret"))
        assertFalse(services.toString().contains("--secret"))

        val processesResult = JSONObject()
            .put("os", "linux")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("pid", 42)
                        .put("name", "sshd")
                        .put("cpu", 2.5)
                        .put("memory", 4096)
                        .put("commandLine", "sshd --secret"),
                ),
            )
        val processes = parseJob(
            baseJob("completed", 0, processesResult, templateId = "processes"),
        ).result as CompanionProcesses
        assertEquals(42L, processes.items.single().pid)
        assertEquals(4096L, processes.items.single().memoryBytes)
        assertFalse(processes.toString().contains("commandLine"))
    }

    @Test
    fun serviceMutationIsVerifiedOnlyWithMatchingStructuredResult() {
        val result = JSONObject()
            .put("os", "linux")
            .put("operation", "service-start")
            .put("name", "Spooler")
            .put("changed", true)
            .put("state", "active")
        val job = parseJob(
            baseJob(
                status = "completed",
                exitCode = 0,
                result = result,
                templateId = "service-start",
                serviceName = "Spooler",
            ),
        )
        assertTrue(job.verifiedComplete)
        val mutation = job.result as CompanionServiceMutation
        assertTrue(mutation.changed)
        assertEquals("active", mutation.state)

        val wrongOperation = parseJob(
            baseJob(
                status = "completed",
                exitCode = 0,
                result = JSONObject(result.toString()).put("operation", "service-stop"),
                templateId = "service-start",
                serviceName = "Spooler",
            ),
        )
        assertTrue(wrongOperation.resultParseFailed)
        assertFalse(wrongOperation.verifiedComplete)
    }

    @Test
    fun eventsAreBoundedAndUnknownKindsAreExplicit() {
        val data = JSONObject()
            .put(
                "items",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "mce_1")
                            .put("sequence", 1)
                            .put("kind", "job-completed")
                            .put("nodeid", "node/domain/device")
                            .put("jobId", "mc_1")
                            .put("time", 1000L),
                    )
                    .put(
                        JSONObject()
                            .put("id", "mce_2")
                            .put("sequence", 2)
                            .put("kind", "future-kind")
                            .put("time", 2000L),
                    )
                    .put(JSONObject().put("sequence", 3)),
            )
            .put("cursor", 3)
            .put("resetCursor", false)
        val page = CompanionParser.parseEvents(data)
        assertEquals(2, page.items.size)
        assertEquals(3L, page.cursor)
        assertEquals(CompanionEventKind.JOB_COMPLETED, page.items[0].kind)
        assertEquals(CompanionEventKind.UNKNOWN, page.items[1].kind)
    }

    @Test
    fun capabilitiesAreParsedFromTheAllowlist() {
        val data = JSONObject()
            .put("protocolVersion", 1)
            .put("schemaVersion", 1)
            .put("operations", JSONArray().put("capabilities").put("startJob"))
            .put("platforms", JSONArray().put("windows").put("linux"))
            .put("events", JSONArray().put("job-completed").put("future-event"))
            .put(
                "templates",
                JSONArray().put(
                    JSONObject()
                        .put("templateId", "diagnostics")
                        .put("kind", "read")
                        .put("parameters", JSONObject()),
                ),
            )
            .put("limits", JSONObject().put("maxJobs", 40).put("eventLimitMax", 100))
        val capabilities = CompanionParser.parseCapabilities(data)
        assertEquals(1, capabilities.protocolVersion)
        assertTrue(capabilities.operations.contains("startJob"))
        assertEquals(setOf(CompanionEventKind.JOB_COMPLETED), capabilities.eventKinds)
        assertEquals(40, capabilities.limits.maxJobs)
        assertTrue(capabilities.supports(CompanionTemplate.DIAGNOSTICS))
    }

    @Test
    fun serverErrorMessagesAreNeverExposed() {
        val client = CompanionClient { _ ->
            JSONObject()
                .put("action", "plugin")
                .put("plugin", "mobilecompanion")
                .put("op", "capabilities")
                .put("ok", false)
                .put(
                    "error",
                    JSONObject()
                        .put("code", "FORBIDDEN")
                        .put("message", "secret internal path /srv/private"),
                )
        }
        val error = assertThrows(CompanionException::class.java) {
            runBlocking { client.capabilities() }
        }
        assertEquals(CompanionErrorCode.FORBIDDEN, error.code)
        assertEquals("FORBIDDEN", error.message)
        assertFalse(error.message.orEmpty().contains("secret internal path"))
    }

    @Test
    fun unknownServerErrorCodeBecomesTypedUnknown() {
        val client = CompanionClient { _ ->
            JSONObject()
                .put("action", "plugin")
                .put("plugin", "mobilecompanion")
                .put("ok", false)
                .put("error", JSONObject().put("code", "FUTURE_CODE").put("message", "do not show"))
        }
        val error = assertThrows(CompanionException::class.java) {
            runBlocking { client.capabilities() }
        }
        assertEquals(CompanionErrorCode.UNKNOWN, error.code)
        assertFalse(error.message.orEmpty().contains("do not show"))
    }

    @Test
    fun malformedJobAndResponseAreProtocolErrors() {
        val missingId = JSONObject()
            .put("nodeid", "node/domain/device")
            .put("templateId", "diagnostics")
            .put("createdAt", 1L)
            .put("status", "completed")
        val parseError = assertThrows(CompanionException::class.java) {
            CompanionParser.parseJob(missingId)
        }
        assertEquals(CompanionErrorCode.PROTOCOL, parseError.code)

        val client = CompanionClient { _ -> JSONObject().put("action", "plugin") }
        val responseError = assertThrows(CompanionException::class.java) {
            runBlocking { client.capabilities() }
        }
        assertEquals(CompanionErrorCode.PROTOCOL, responseError.code)
    }

    @Test
    fun listJobsSkipsMalformedEntriesWithoutLosingValidOnes() {
        val data = JSONObject().put(
            "jobs",
            JSONArray()
                .put(baseJob("completed", 0, JSONObject().put("os", "linux").put("hostname", "host")))
                .put(JSONObject().put("id", "too-short")),
        )
        val jobs = CompanionParser.parseJobs(data)
        assertEquals(1, jobs.size)
        assertNotNull(jobs.single().id)
    }

    private fun baseJob(
        status: String,
        exitCode: Int?,
        result: JSONObject?,
        templateId: String = "diagnostics",
        serviceName: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", "mc_test")
        put("nodeid", "node/domain/device")
        put("templateId", templateId)
        put(
            "parameters",
            if (serviceName != null) JSONObject().put("name", serviceName) else JSONObject(),
        )
        put("createdAt", 1_000L)
        put("status", status)
        if (exitCode != null) put("exitCode", exitCode)
        if (result != null) put("result", result)
    }

    private fun parseJob(raw: JSONObject): CompanionJob = CompanionParser.parseJob(raw)
}
