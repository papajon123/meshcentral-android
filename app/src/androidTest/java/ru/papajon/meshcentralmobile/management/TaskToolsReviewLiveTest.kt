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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.tasks.*
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException
import ru.papajon.meshcentralmobile.mesh.session.*
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.management.TaskToolsScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.util.concurrent.TimeUnit

/** Uses one helper-owned temporary account, never existing users. Faults are injected only
 * into that user's agent replies; the local helper removes the account after acceptance. */
class TaskToolsReviewLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun restrictedUserEmptyAgentReplyAndAgentRestart() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskToolsReviewLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient.Builder().readTimeout(45, TimeUnit.SECONDS).build()
        suspend fun helper(path: String, post: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("http://10.0.2.2:19880/$path").apply { if (post) post(ByteArray(0).toRequestBody()) }.build()
            http.newCall(request).execute().use { check(it.isSuccessful) { "Local review helper failed" }; JSONObject(requireNotNull(it.body).string()) }
        }
        val fixture = helper("review-fixture", false)
        check(fixture.getString("serverUrl") == "https://10.0.2.2:4443")
        check(fixture.getString("username").matches(Regex("processes-review-[0-9a-f]{16}")))
        val nodeId = fixture.getString("nodeId")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val vault = SecureVault(context, "review5680", "review5680_test_vault")
        val client = MeshCentralClient(vault, scope)
        var shown by mutableStateOf(true)
        var services by mutableStateOf(false)
        var screen by mutableStateOf<TaskToolsController?>(null)
        var terminal: RelayConnection? = null
        var ownProcess: AgentProcess? = null
        var wire: TaskToolsTransport? = null
        val evidence = JSONObject()
        try {
            withContext(Dispatchers.Main) {
                client.configureServer(fixture.getString("serverUrl")); client.setPinnedServerKey(fixture.getString("pin"))
                client.login(fixture.getString("serverUrl"), fixture.getString("username"), fixture.getString("password"), rememberDevice = false)
            }
            fixture.remove("password")
            withTimeout(20_000) { client.clientState.first { it.devicesCurrent && it.devices.any { d -> d.nodeId == nodeId && d.conn and 1 != 0 } } }
            val transport = client.taskTools(nodeId); wire = transport
            check(transport.device!!.rights!! and 4096 != 0)
            val processes = TaskToolsController(transport, false, scope); screen = processes
            compose.setContent {
                val live by client.clientState.collectAsState()
                val device = live.devices.firstOrNull { it.nodeId == nodeId }
                if (shown && screen != null && device != null) MeshCentralMobileTheme {
                    TaskToolsScreen(screen!!, device, live.socketConnected && live.devicesCurrent, services, {}, features2 = live.serverCapabilities.features2)
                }
            }
            compose.waitUntil(20_000) { processes.state.value.loaded && !processes.state.value.loading }
            val sample = processes.state.value.processes.first { it.pid == 1L }
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasTestTag("process_${sample.pid}"))
            compose.onNodeWithTag("process_${sample.pid}").assertIsDisplayed()
            compose.onNodeWithTag("kill_${sample.pid}").assertDoesNotExist()
            evidence.put("limitedProcessActionsHidden", true)
            val serviceScreen = TaskToolsController(transport, true, scope)
            compose.runOnIdle { processes.close(); screen = serviceScreen; services = true }
            compose.waitUntil(20_000) { serviceScreen.state.value.loaded && !serviceScreen.state.value.loading }
            val service = serviceScreen.state.value.services.first()
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasTestTag("service_${service.name}"))
            compose.onNodeWithTag("service_${service.name}").assertIsDisplayed()
            TaskServiceAction.entries.forEach { compose.onNodeWithTag("service_${it.name}_${service.name}").assertDoesNotExist() }
            evidence.put("limitedServiceActionsHidden", true)

            helper("grant-control")
            withTimeout(15_000) { client.clientState.first { state -> state.devices.firstOrNull { it.nodeId == nodeId }?.let {
                TaskToolsProtocol.canMutate(it, state.devicesCurrent && state.socketConnected, state.serverCapabilities.features2)
            } == true } }
            val socketField = MeshCentralClient::class.java.getDeclaredField("controlSocket").apply { isAccessible = true }
            val originalSocket = socketField.get(client)
            val originalGeneration = transport.generation
            val cancelledScreen = TaskToolsController(transport, false, scope)
            compose.runOnIdle { serviceScreen.close(); screen = cancelledScreen; services = false }
            compose.waitUntil(20_000) { cancelledScreen.state.value.loaded && !cancelledScreen.state.value.loading }
            helper("arm-delay")
            compose.onNodeWithTag("tasks_refresh").performClick()
            helper("wait-delayed")
            assertTrue(cancelledScreen.state.value.loading)
            compose.runOnIdle { shown = false; cancelledScreen.close() }
            val returnedScreen = TaskToolsController(transport, false, scope)
            compose.runOnIdle { screen = returnedScreen; shown = true }
            compose.waitUntil(25_000) { returnedScreen.state.value.loaded && !returnedScreen.state.value.loading && returnedScreen.state.value.verificationReady }
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasTestTag("kill_1"))
            compose.onNodeWithTag("kill_1").assertIsEnabled()
            assertEquals(originalGeneration, transport.generation)
            assertTrue(originalSocket === socketField.get(client))
            evidence.put("cancelledReadRestoredActionsWithoutReconnect", true)
            android.util.Log.i("TaskToolsReviewLive", evidence.toString())
            val relay = client.createRelayConnection(transport.device!!, RelayProtocol.AgentTerminal, JSONObject().put("cols", 80).put("rows", 24))
            terminal = relay
            val pid = CompletableDeferred<Long>()
            val reader = scope.launch(Dispatchers.Default) {
                val buffer = StringBuilder()
                relay.incoming.collect { frame ->
                    buffer.append(when (frame) { is RelayFrame.Binary -> frame.value.toString(Charsets.UTF_8); is RelayFrame.Text -> frame.value })
                    Regex("__REVIEW_OWN_PID__(\\d+)").find(buffer)?.groupValues?.get(1)?.toLongOrNull()?.let { pid.complete(it) }
                    if (buffer.length > 8192) buffer.delete(0, buffer.length - 4096)
                }
            }
            relay.connect(); withTimeout(20_000) { relay.state.first { it.phase == RelayPhase.Connected } }
            check(relay.sendBinary("sleep 86423 & echo __REVIEW_OWN_PID__\$!\r".toByteArray()))
            val ownPid = withTimeout(15_000) { pid.await() }; reader.cancel(); check(ownPid > 4)
            val actionScreen = TaskToolsController(transport, false, scope)
            compose.runOnIdle { returnedScreen.close(); screen = actionScreen; services = false }
            compose.waitUntil(20_000) { actionScreen.state.value.processes.any { it.pid == ownPid } && !actionScreen.state.value.loading }
            ownProcess = actionScreen.state.value.processes.first { it.pid == ownPid }.also { check(it.name.contains("sleep")) }
            compose.onNodeWithTag("tasks_search").performTextInput(ownPid.toString())
            compose.onNodeWithTag("tasks_list").performScrollToNode(hasTestTag("kill_$ownPid"))
            compose.onNodeWithTag("kill_$ownPid").performClick()
            helper("arm-empty") // Real agent rewrites only this user's next post-command ps reply.
            compose.onNodeWithTag("tasks_confirm").performClick()
            compose.waitUntil(20_000) { !actionScreen.state.value.mutating && actionScreen.state.value.notice != null }
            assertEquals(R.string.tasks_unverified, actionScreen.state.value.notice)
            assertEquals(R.string.tasks_empty_snapshot, actionScreen.state.value.error)
            assertTrue(actionScreen.state.value.processes.isEmpty())
            evidence.put("emptyAgentReplyObserved", true).put("emptyReplyDidNotConfirmSuccess", true)
            val actual = TaskToolsProtocol.processes(transport.read("ps"))
            assertFalse(actual.any { it.pid == ownPid }); ownProcess = null

            compose.runOnIdle { shown = false; actionScreen.close() }
            val generation = transport.generation
            val controlSocket = socketField.get(client)
            helper("arm-hold")
            // The fixture permits a 90-second graceful stop; keep this test read pending until the offline event.
            val pending = async { runCatching { transport.read("ps", timeoutMillis = 120_000) } }
            val offline = async(start = CoroutineStart.UNDISPATCHED) {
                client.clientState.first { it.devices.firstOrNull { d -> d.nodeId == nodeId }?.conn?.and(1) == 0 }
            }
            helper("restart-agent") // Helper waits until the real agent has held this user's reply.
            val failure = withTimeout(125_000) { pending.await().exceptionOrNull() }
            assertTrue(failure is ControlRequestException && failure.code == "agent_disconnected")
            withTimeout(5_000) { offline.await() }
            withTimeout(40_000) { client.clientState.first { it.devices.firstOrNull { d -> d.nodeId == nodeId }?.conn?.and(1) == 1 } }
            assertTrue(transport.generation != generation)
            assertTrue("The control channel must stay connected", controlSocket === socketField.get(client))
            val recovered = transport.read("ps")
            assertTrue(TaskToolsBroker.verifiable(recovered)); assertTrue(TaskToolsProtocol.processes(recovered).isNotEmpty())
            evidence.put("requestCancelledOnAgentOffline", true).put("freshReadAfterAgentRestart", true).put("controlSocketPreserved", true)
            android.util.Log.i("TaskToolsReviewLive", evidence.toString())
        } finally {
            screen?.close()
            ownProcess?.let { target -> runCatching { wire?.let { t ->
                if (TaskToolsProtocol.processes(t.read("ps")).any { it.pid == target.pid && it.name == target.name }) t.kill(target, true)
            } } }
            terminal?.close(); client.close(); vault.clearCredentials(); scope.cancel()
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
    }
}
