package ru.papajon.meshcentralmobile.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionControllerTest {
    @Test
    fun timedOutStartIsNotRetriedAndExplicitRecheckReusesTheOperation() = runBlocking {
        val transport = FakeTransport()
        transport.startResponder = { withTimeout(1) { awaitCancellation() } }
        val (controller, scope) = newController(transport)
        try {
            controller.refresh().join()
            controller.startDiagnostics().join()

            assertEquals(1, transport.startCount)
            val pending = controller.state.value.pendingOperation
            assertNotNull(pending)
            assertTrue(controller.state.value.errorUncertain)
            val first = transport.startCommands.single()
            val operationId = first.getString("operationId")
            val createdAt = first.getLong("createdAt")

            // No automatic retry is allowed while the operation remains unresolved.
            assertEquals(1, transport.startCount)

            transport.startResponder = { command -> ok(JSONObject().put("job", jobFor(command))) }
            controller.recheckPendingOperation().join()

            assertEquals(2, transport.startCount)
            val second = transport.startCommands[1]
            assertEquals(operationId, second.getString("operationId"))
            assertEquals(createdAt, second.getLong("createdAt"))
            assertNull(controller.state.value.pendingOperation)
            assertNotNull(controller.state.value.selectedJob)
            assertFalse(controller.state.value.errorUncertain)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun definitiveServerFailureClearsPendingAndIsNotRetried() = runBlocking {
        val transport = FakeTransport()
        transport.startResponder = { error("AGENT_OFFLINE") }
        val (controller, scope) = newController(transport)
        try {
            controller.refresh().join()
            controller.startDiagnostics().join()

            assertEquals(CompanionErrorCode.AGENT_OFFLINE, controller.state.value.error)
            assertNull(controller.state.value.pendingOperation)
            assertFalse(controller.state.value.errorUncertain)
            assertEquals(1, transport.startCount)

            controller.recheckPendingOperation().join()
            assertEquals(1, transport.startCount)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun queuedJobCanBeCancelledButRunningJobIsReportedUnavailable() = runBlocking {
        val queued = job(id = "mc_queued", status = "queued", exitCode = null)
        val running = job(id = "mc_running", status = "running", exitCode = null)
        val queuedTransport = FakeTransport().apply {
            jobs = listOf(queued)
            cancelResponse = ok(JSONObject().put("job", job(id = "mc_queued", status = "cancelled", exitCode = null)))
        }
        val (queuedController, queuedScope) = newController(queuedTransport)
        try {
            queuedController.refresh().join()
            queuedController.cancelJob("mc_queued").join()
            assertEquals(CompanionJobStatus.CANCELLED, queuedController.state.value.selectedJob?.status)
            assertEquals(1, queuedTransport.cancelCount)
        } finally {
            queuedController.close()
            queuedScope.cancel()
        }

        val runningTransport = FakeTransport().apply { jobs = listOf(running) }
        val (runningController, runningScope) = newController(runningTransport)
        try {
            runningController.refresh().join()
            runningController.cancelJob("mc_running").join()
            assertEquals(CompanionErrorCode.CANCEL_UNSUPPORTED, runningController.state.value.error)
            assertEquals(0, runningTransport.cancelCount)
        } finally {
            runningController.close()
            runningScope.cancel()
        }
    }

    @Test
    fun deleteUnsupportedByV1ServerKeepsHistory() = runBlocking {
        val transport = FakeTransport().apply {
            jobs = listOf(job(id = "mc_done", status = "completed"))
            deleteResponse = error("UNKNOWN_OP")
        }
        val (controller, scope) = newController(transport)
        try {
            controller.refresh().join()
            controller.clearHistory().join()
            assertEquals(CompanionErrorCode.UNKNOWN_OP, controller.state.value.error)
            assertEquals(1, controller.state.value.jobs.size)
            assertEquals(1, transport.deleteCount)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun closeCancelsControllerScopeAndLeavesClientCallsAlone() = runBlocking {
        val transport = FakeTransport()
        val (controller, scope) = newController(transport)
        try {
            controller.refresh().join()
            controller.close()
            controller.startDiagnostics().join()
            assertEquals(0, transport.startCount)
            assertEquals(0, transport.startCommands.size)
        } finally {
            scope.cancel()
        }
    }

    private fun newController(
        transport: FakeTransport,
    ): Pair<CompanionController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CompanionController(
            parentScope = scope,
            client = CompanionClient(transport::request),
            nodeId = NODE_ID,
        )
        return controller to scope
    }

    private class FakeTransport {
        val commands = mutableListOf<JSONObject>()
        var jobs: List<JSONObject> = emptyList()
        var startResponder: suspend (JSONObject) -> JSONObject = { command ->
            ok(JSONObject().put("job", jobFor(command)))
        }
        var cancelResponse: JSONObject = error("CANCEL_UNSUPPORTED")
        var deleteResponse: JSONObject = error("UNKNOWN_OP")

        val startCommands: List<JSONObject>
            get() = commands.filter { it.optString("op") == "startJob" }
        val startCount: Int get() = startCommands.size
        val cancelCount: Int get() = commands.count { it.optString("op") == "cancelJob" }
        val deleteCount: Int get() = commands.count { it.optString("op") == "deleteJob" }

        suspend fun request(command: JSONObject): JSONObject {
            commands += JSONObject(command.toString())
            return when (command.optString("op")) {
                "capabilities" -> ok(
                    JSONObject()
                        .put("protocolVersion", 1)
                        .put("schemaVersion", 1)
                        .put("operations", JSONArray().put("startJob"))
                        .put("templates", JSONArray()),
                )
                "listJobs" -> {
                    val array = JSONArray()
                    jobs.forEach { array.put(it) }
                    ok(JSONObject().put("jobs", array))
                }
                "events" -> ok(
                    JSONObject()
                        .put("items", JSONArray())
                        .put("cursor", 0)
                        .put("resetCursor", false),
                )
                "startJob" -> startResponder(command)
                "cancelJob" -> cancelResponse
                "deleteJob" -> deleteResponse
                "getJob" -> ok(JSONObject().put("job", job(id = "mc_test")))
                else -> error("UNKNOWN_OP")
            }
        }
    }
}

private const val NODE_ID = "node/domain/device"

private fun ok(data: JSONObject): JSONObject = JSONObject()
    .put("action", "plugin")
    .put("plugin", "mobilecompanion")
    .put("ok", true)
    .put("data", data)

private fun error(code: String): JSONObject = JSONObject()
    .put("action", "plugin")
    .put("plugin", "mobilecompanion")
    .put("ok", false)
    .put("error", JSONObject().put("code", code).put("message", "server detail not for UI"))

private fun jobFor(command: JSONObject): JSONObject = job(
    id = "mc_test",
    template = command.optString("templateId").ifBlank { "diagnostics" },
    status = "completed",
    exitCode = 0,
    createdAt = command.optLong("createdAt", 1_000L),
)

private fun job(
    id: String = "mc_test",
    template: String = "diagnostics",
    status: String = "completed",
    exitCode: Int? = 0,
    createdAt: Long = 1_000L,
): JSONObject = JSONObject().apply {
    put("id", id)
    put("nodeid", NODE_ID)
    put("templateId", template)
    put("parameters", JSONObject())
    put("createdAt", createdAt)
    put("status", status)
    if (exitCode != null) put("exitCode", exitCode)
    if (status == "completed" && template == "diagnostics") {
        put(
            "result",
            JSONObject()
                .put("os", "linux")
                .put("hostname", "host-1")
                .put("memory", JSONObject().put("totalBytes", 1000L).put("usedPercent", 50)),
        )
    }
}
