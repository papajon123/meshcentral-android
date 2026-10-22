package ru.papajon.meshcentralmobile.companion

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.ui.screens.companion.CompanionScreen
import ru.papajon.meshcentralmobile.ui.screens.companion.CompanionTestTags
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class CompanionScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val openControllers = mutableListOf<Pair<CompanionController, CoroutineScope>>()

    @After
    fun tearDown() {
        openControllers.forEach { (controller, scope) ->
            controller.close()
            scope.cancel()
        }
        openControllers.clear()
    }

    @Test
    fun screenShowsChecksHistoryAndQuickHelp() {
        val transport = FakeTransport()
        val controller = newController(transport)
        show(controller)

        compose.waitUntil(5_000) { controller.state.value.available }
        compose.onNodeWithTag(CompanionTestTags.Screen).assertExists()
        scrollTo(CompanionTestTags.RunDiagnostics)
        compose.onNodeWithTag(CompanionTestTags.RunDiagnostics).assertExists()

        compose.onNodeWithTag(CompanionTestTags.Content)
            .performScrollToNode(hasTestTag(CompanionTestTags.JobsSection))
        compose.onNodeWithTag(CompanionTestTags.JobsSection).assertExists()

        compose.onNodeWithTag(CompanionTestTags.Content)
            .performScrollToNode(hasTestTag(CompanionTestTags.QuickHelp))
        compose.onNodeWithTag(CompanionTestTags.QuickHelp).assertExists()
    }

    @Test
    fun diagnosticsDisclosureRequiresExplicitConfirmation() {
        val transport = FakeTransport()
        val controller = newController(transport)
        show(controller)
        compose.waitUntil(5_000) { controller.state.value.available }

        scrollTo(CompanionTestTags.RunDiagnostics)
        compose.onNodeWithTag(CompanionTestTags.RunDiagnostics)
            .performClick()
        compose.onNodeWithTag(CompanionTestTags.DisclosureDialog).assertExists()

        compose.onNodeWithTag(CompanionTestTags.DisclosureCancel).performClick()
        compose.onNodeWithTag(CompanionTestTags.DisclosureDialog).assertDoesNotExist()
        assertEquals(0, transport.startCount.get())

        compose.onNodeWithTag(CompanionTestTags.RunDiagnostics).performClick()
        compose.onNodeWithTag(CompanionTestTags.DisclosureConfirm).performClick()
        compose.waitUntil(5_000) { transport.startCount.get() == 1 }
        compose.onNodeWithTag(CompanionTestTags.DisclosureDialog).assertDoesNotExist()
    }

    @Test
    fun serviceActionNeedsConfirmationAndSendsTheChosenService() {
        val transport = FakeTransport()
        val controller = newController(transport)
        show(controller)
        compose.waitUntil(5_000) { controller.state.value.available }

        scrollTo(CompanionTestTags.RunServices)
        compose.onNodeWithTag(CompanionTestTags.RunServices)
            .performClick()
        compose.onNodeWithTag(CompanionTestTags.DisclosureConfirm).performClick()
        compose.waitUntil(5_000) {
            controller.state.value.jobs.any { it.template == CompanionTemplate.SERVICES }
        }

        compose.onNodeWithTag(CompanionTestTags.Content)
            .performScrollToNode(hasTestTag(CompanionTestTags.serviceRow("Spooler")))
        compose.onNodeWithTag(CompanionTestTags.serviceMenu("Spooler"))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(
            CompanionTestTags.serviceAction("Spooler", CompanionServiceAction.START),
        ).performClick()

        compose.onNodeWithTag(CompanionTestTags.ServiceConfirmDialog).assertIsDisplayed()
        compose.onNodeWithTag(CompanionTestTags.ServiceCancel).performClick()
        compose.onNodeWithTag(CompanionTestTags.ServiceConfirmDialog).assertDoesNotExist()
        assertEquals(1, transport.startCount.get())

        compose.onNodeWithTag(CompanionTestTags.serviceMenu("Spooler"))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(
            CompanionTestTags.serviceAction("Spooler", CompanionServiceAction.START),
        ).performClick()
        compose.onNodeWithTag(CompanionTestTags.ServiceConfirm).performClick()
        compose.waitUntil(5_000) { transport.startCount.get() == 2 }

        compose.runOnIdle {
            val command = transport.commands.last { it.optString("op") == "startJob" }
            assertEquals("service-start", command.optString("templateId"))
            assertEquals(
                "Spooler",
                command.optJSONObject("parameters")?.optString("name"),
            )
        }
    }

    private fun show(controller: CompanionController) {
        compose.setContent {
            MeshCentralMobileTheme {
                CompanionScreen(
                    controller = controller,
                    deviceName = "Night PC",
                    onBack = {},
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(CompanionTestTags.Content)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun newController(transport: FakeTransport): CompanionController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = CompanionController(
            parentScope = scope,
            client = CompanionClient(transport::request),
            nodeId = NODE_ID,
        )
        openControllers += controller to scope
        return controller
    }

    private class FakeTransport {
        val commands = CopyOnWriteArrayList<JSONObject>()
        val startCount = AtomicInteger()

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
                "listJobs" -> ok(JSONObject().put("jobs", JSONArray()))
                "events" -> ok(
                    JSONObject()
                        .put("items", JSONArray())
                        .put("cursor", 0)
                        .put("resetCursor", false),
                )
                "startJob" -> {
                    val sequence = startCount.incrementAndGet()
                    ok(JSONObject().put("job", completedJob(command, sequence)))
                }
                "deleteJob" -> error("UNKNOWN_OP")
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
    .put("error", JSONObject().put("code", code))

private fun completedJob(command: JSONObject, sequence: Int): JSONObject {
    val template = command.optString("templateId")
    return JSONObject().apply {
        put("id", "mc_$sequence")
        put("nodeid", NODE_ID)
        put("templateId", template)
        put("parameters", command.optJSONObject("parameters") ?: JSONObject())
        put("createdAt", command.optLong("createdAt", 1_000L))
        put("startedAt", command.optLong("createdAt", 1_000L) + 10)
        put("finishedAt", command.optLong("createdAt", 1_000L) + 20)
        put("status", "completed")
        put("exitCode", 0)
        when (template) {
            "diagnostics" -> put(
                "result",
                JSONObject()
                    .put("os", "linux")
                    .put("hostname", "host-1")
                    .put("osDescription", "Ubuntu 22.04")
                    .put("uptimeSeconds", 3_600)
                    .put(
                        "cpu",
                        JSONObject().put("logicalCount", 4).put("load1", 0.5),
                    )
                    .put(
                        "memory",
                        JSONObject()
                            .put("totalBytes", 1_000_000L)
                            .put("availableBytes", 400_000L)
                            .put("usedPercent", 60),
                    )
                    .put(
                        "disks",
                        JSONArray().put(
                            JSONObject()
                                .put("device", "/dev/sda1")
                                .put("mount", "/")
                                .put("totalBytes", 1_000_000L)
                                .put("availableBytes", 100_000L)
                                .put("usedPercent", 91),
                        ),
                    )
                    .put("network", JSONArray()),
            )
            "services" -> put(
                "result",
                JSONObject()
                    .put("os", "linux")
                    .put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("name", "Spooler")
                                .put("displayName", "Print Spooler")
                                .put("state", "Stopped")
                                .put("startMode", "Manual"),
                        ),
                    )
                    .put("truncated", false),
            )
            "processes" -> put(
                "result",
                JSONObject()
                    .put("os", "linux")
                    .put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("pid", 42)
                                .put("name", "sshd")
                                .put("cpu", 1.5)
                                .put("memory", 4096),
                        ),
                    )
                    .put("truncated", false),
            )
            "service-start" -> put(
                "result",
                JSONObject()
                    .put("os", "linux")
                    .put("operation", "service-start")
                    .put(
                        "name",
                        command.optJSONObject("parameters")?.optString("name")
                            ?: "Spooler",
                    )
                    .put("changed", true)
                    .put("state", "Running"),
            )
        }
    }
}
