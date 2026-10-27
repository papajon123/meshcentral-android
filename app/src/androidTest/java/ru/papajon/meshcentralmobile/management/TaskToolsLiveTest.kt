package ru.papajon.meshcentralmobile.management

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.tasks.*
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.session.*
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.management.TaskToolsScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File

/** Explicit opt-in; this test can only connect to the isolated loopback fixture. */
class TaskToolsLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun localLinuxListsAndEndsOnlyItsOwnSleepThroughScreen() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskToolsLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val fixture = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("http://10.0.2.2:19880/task-tools-5680-fixture").build()).execute().use {
                check(it.isSuccessful); JSONObject(requireNotNull(it.body).string())
            }
        }
        check(fixture.getString("serverUrl") == "https://10.0.2.2:4443")
        val vault = SecureVault(context, "processes5680", "processes5680_test_vault")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val client = MeshCentralClient(vault, scope)
        var relay: RelayConnection? = null
        var controller: TaskToolsController? = null
        var target: AgentProcess? = null
        var transport: TaskToolsTransport? = null
        val evidence = JSONObject()
        try {
            withContext(Dispatchers.Main) {
                client.configureServer(fixture.getString("serverUrl"))
                client.setPinnedServerKey(fixture.getString("pin"))
                client.login(fixture.getString("serverUrl"), fixture.getString("username"), fixture.getString("password"), rememberDevice = false)
            }
            fixture.remove("password")
            val state = withTimeout(20_000) { client.clientState.first { it.socketConnected && it.devicesCurrent && it.devices.any { d -> d.agentId == 6 && d.conn and 1 != 0 } } }
            val device = state.devices.first { it.agentId == 6 && it.conn and 1 != 0 && it.osDescription.contains("Ubuntu") }
            val wire = client.taskTools(device.nodeId)
            transport = wire
            val initial = TaskToolsProtocol.processes(wire.read("ps"))
            assertTrue(initial.isNotEmpty())
            evidence.put("processCount", initial.size)
            val services = TaskToolsProtocol.services(wire.read("services"))
            assertTrue(services.isNotEmpty())
            evidence.put("serviceCount", services.size)
            evidence.put("runningServices", services.count { it.running == true })
            val terminal = client.createRelayConnection(device, RelayProtocol.AgentTerminal, JSONObject().put("cols", 80).put("rows", 24))
            relay = terminal
            val pid = CompletableDeferred<Long>()
            val reader = scope.launch(Dispatchers.Default) {
                val text = StringBuilder()
                terminal.incoming.collect { frame ->
                    text.append(when (frame) { is RelayFrame.Binary -> frame.value.toString(Charsets.UTF_8); is RelayFrame.Text -> frame.value })
                    Regex("__MESH_TASK_PID__(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { pid.complete(it) }
                    if (text.length > 8192) text.delete(0, text.length - 4096)
                }
            }
            terminal.connect()
            withTimeout(20_000) { terminal.state.first { it.phase == RelayPhase.Connected } }
            check(terminal.sendBinary("sleep 86391 & echo __MESH_TASK_PID__\$!\r".toByteArray()))
            val ownPid = withTimeout(15_000) { pid.await() }
            assertTrue(ownPid > 4)
            reader.cancel()
            target = withTimeout(10_000) {
                var match: AgentProcess? = null
                while (match == null) { delay(300); match = TaskToolsProtocol.processes(wire.read("ps")).firstOrNull { it.pid == ownPid && it.name.contains("sleep") } }
                match
            }
            val processesController = TaskToolsController(wire, false, scope)
            controller = processesController
            var displayedController by mutableStateOf(processesController)
            var servicesMode by mutableStateOf(false)
            compose.setContent {
                MeshCentralMobileTheme { TaskToolsScreen(displayedController, device, true, servicesMode, {}) }
            }
            compose.waitUntil(20_000) { processesController.state.value.loaded && !processesController.state.value.loading }
            compose.onNodeWithTag("tasks_search").performTextInput(ownPid.toString())
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("kill_$ownPid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("kill_$ownPid").performScrollTo().performClick()
            compose.onNodeWithTag("tasks_confirm").performClick()
            compose.waitUntil(20_000) { processesController.state.value.notice == R.string.tasks_killed }
            val final = TaskToolsProtocol.processes(wire.read("ps"))
            assertFalse(final.any { it.pid == ownPid })
            evidence.put("ownedSleepAbsent", true).put("commandSentThroughScreen", true)
            target = null
            processesController.close()
            val serviceController = TaskToolsController(wire, true, scope)
            controller = serviceController
            compose.runOnIdle { displayedController = serviceController; servicesMode = true }
            compose.waitUntil(20_000) { serviceController.state.value.loaded }
            evidence.put("servicesScreenLoaded", true)
            android.util.Log.i("TaskToolsLive", evidence.toString())
            File(context.getExternalFilesDir(null), "task-tools-live.json").writeText(evidence.toString(2))
        } finally {
            controller?.close()
            // The only cleanup candidate is the PID returned by this test's own terminal command.
            target?.let { own ->
                runCatching { transport?.let { wire ->
                    if (TaskToolsProtocol.processes(wire.read("ps")).any { it.pid == own.pid && it.name == own.name }) wire.kill(own, true)
                } }
            }
            relay?.close()
            client.close()
            vault.clearCredentials()
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
