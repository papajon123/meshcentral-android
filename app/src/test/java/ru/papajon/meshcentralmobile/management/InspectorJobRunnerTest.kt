package ru.papajon.meshcentralmobile.management

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.*
import ru.papajon.meshcentralmobile.localization.UiText

class InspectorJobRunnerTest {
    private val node = "node//fixture"

    @Test fun requestFailuresKeepTheirReasonAndDiscardServerText() = runBlocking {
        for ((code, resource) in listOf(
            "FORBIDDEN" to R.string.companion_error_forbidden,
            "AGENT_OFFLINE" to R.string.companion_error_agent_offline,
            "NODE_BUSY" to R.string.companion_error_node_busy,
            "QUOTA_EXCEEDED" to R.string.companion_error_quota_exceeded,
            "unexpected-secret-code" to R.string.companion_error_unknown,
        )) {
            var starts = 0
            val client = CompanionClient { command ->
                assertEquals("startJob", command.getString("op"))
                starts++
                envelope(false).put("error", JSONObject().put("code", code).put("message", "private-session-secret"))
            }
            val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
            assertEquals(UiText.resource(resource), inspectorFailureText(error))
            assertFalse(inspectorFailureText(error).toString().contains("private-session-secret"))
            assertEquals(1, starts)
        }
    }

    @Test fun terminalFailuresPreserveTimeoutRestartAndCommandReasons() = runBlocking {
        for ((code, resource) in listOf(
            "TIMEOUT" to R.string.companion_job_error_timeout,
            "SERVER_RESTART" to R.string.companion_job_error_server_restart,
            "COMMAND_FAILED" to R.string.companion_job_error_command_failed,
            "INVALID_RESULT" to R.string.companion_job_error_invalid_result,
            "AGENT_WAIT_TIMEOUT" to R.string.companion_job_error_agent_wait_timeout,
        )) {
            var starts = 0
            val client = CompanionClient {
                starts++
                ok(job("unknown").put("errorCode", code))
            }
            val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
            assertEquals(UiText.resource(resource), inspectorFailureText(error))
            assertEquals(1, starts)
        }
    }

    @Test fun pollingReadsTheSameJobAndReturnsOnlyVerifiedData() = runBlocking {
        var starts = 0
        var polls = 0
        val client = CompanionClient { command ->
            when (command.getString("op")) {
                "startJob" -> { starts++; ok(job("running")) }
                "getJob" -> {
                    assertEquals("fixture-job", command.getString("jobId"))
                    polls++
                    ok(if (polls == 1) job("running") else completed())
                }
                else -> error("unexpected operation")
            }
        }
        val result = runner(client).run(CompanionTemplate.SERVICES) as CompanionServices
        assertEquals("Example service", result.items.single().displayName)
        assertEquals(1, starts)
        assertEquals(2, polls)
    }

    @Test fun wrongJobNodeOrTemplateCannotReplaceRequestedResult() = runBlocking {
        for ((field, value) in listOf("id" to "other-job", "nodeid" to "node//other", "templateId" to "processes")) {
            var starts = 0
            val client = CompanionClient { command ->
                if (command.getString("op") == "startJob") { starts++; ok(job("running")) }
                else ok(completed().put(field, value))
            }
            val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
            assertEquals(UiText.resource(R.string.companion_error_protocol), inspectorFailureText(error))
            assertEquals(1, starts)
        }
    }

    @Test fun completedStatusWithNonzeroExitIsStillFailure() = runBlocking {
        val client = CompanionClient { ok(completed().put("exitCode", 7)) }
        val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
        assertEquals(UiText.resource(R.string.companion_job_error_command_failed), inspectorFailureText(error))
    }

    @Test fun malformedCompletedResultIsNotShownAsSuccess() = runBlocking {
        val client = CompanionClient { ok(completed().put("result", "not-an-object")) }
        val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
        assertEquals(UiText.resource(R.string.companion_job_error_invalid_result), inspectorFailureText(error))
    }

    @Test fun cancelledJobHasAnHonestTerminalMessage() = runBlocking {
        val client = CompanionClient { ok(job("cancelled")) }
        val error = failure { runner(client).run(CompanionTemplate.SERVICES) }
        assertEquals(UiText.resource(R.string.companion_status_cancelled), inspectorFailureText(error))
    }

    @Test fun losingConnectionAfterMutationStartDoesNotReplayIt() = runBlocking {
        var starts = 0
        val client = CompanionClient { command ->
            if (command.getString("op") == "startJob") {
                starts++
                assertTrue(command.getBoolean("confirmed"))
                assertEquals("ExampleService", command.getJSONObject("parameters").getString("name"))
                ok(job("running").put("templateId", "service-restart"))
            } else throw IOException("private-connection-details")
        }
        val error = failure { runner(client).run(CompanionTemplate.SERVICE_RESTART, mapOf("name" to "ExampleService"), true) }
        assertEquals(UiText.resource(R.string.companion_error_network), inspectorFailureText(error))
        assertEquals(1, starts)
    }

    @Test fun pollingDeadlineIsReportedAsTimeoutWithoutStartingAgain() = runBlocking {
        var starts = 0
        val client = CompanionClient { starts++; ok(job("running")) }
        val error = failure {
            InspectorJobRunner(client, node, pollIntervalMillis = 100, timeoutMillis = 5).run(CompanionTemplate.SERVICES)
        }
        assertTrue(error is TimeoutCancellationException)
        assertEquals(UiText.resource(R.string.companion_error_timeout), inspectorFailureText(error))
        assertEquals(1, starts)
    }

    @Test fun lifecycleCancellationPropagatesInsteadOfBecomingAnErrorBanner() = runBlocking {
        val cancellation = CancellationException("closing screen")
        val client = CompanionClient { throw cancellation }
        assertSame(cancellation, failure { runner(client).run(CompanionTemplate.SERVICES) })
        try { inspectorFailureText(cancellation); fail("Cancellation must propagate") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    private fun runner(client: CompanionClient) = InspectorJobRunner(client, node, pollIntervalMillis = 0)
    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected failure")
    }
    private fun envelope(ok: Boolean) = JSONObject().put("action", "plugin").put("plugin", "mobilecompanion").put("ok", ok)
    private fun ok(job: JSONObject) = envelope(true).put("data", JSONObject().put("job", job))
    private fun job(status: String) = JSONObject().put("id", "fixture-job").put("nodeid", node)
        .put("templateId", "services").put("status", status).put("createdAt", 1_000)
        .put("parameters", JSONObject()).put("truncated", false)
    private fun completed() = job("completed").put("exitCode", 0).put("result", JSONObject()
        .put("os", "windows").put("truncated", false).put("items", JSONArray().put(JSONObject()
            .put("name", "ExampleService").put("displayName", "Example service").put("state", "Running"))))
}
