package ru.papajon.meshcentralmobile.management.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshControlParser
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

class TaskToolsCancellationTest {
    private fun command(type: String = "ps") = JSONObject().put("action", "msg").put("nodeid", "node//test").put("type", type)
    private fun processes() = command().put("value", "{\"1\":{\"pid\":1,\"cmd\":\"init\"},\"2\":{\"pid\":2,\"cmd\":\"worker\"}}")

    @Test fun leavingDuringReadDrainsLateReplyAndRestoresMutationsWithoutReconnect() = runBlocking<Unit> {
        val sent = mutableListOf<JSONObject>()
        val broker = TaskToolsBroker { sent.add(it); true }
        val wire = object : TaskToolsTransport {
            override val device = MeshDevice("node//test", "mesh//test", "Test", conn = 1, agentId = 6, rights = 8)
            override val current = true
            override suspend fun read(type: String, pid: Long?, timeoutMillis: Long) = broker.request(command(type), timeoutMillis)
            override suspend fun serviceDetails(name: String): JSONObject = error("unused")
            override fun kill(process: AgentProcess, confirmed: Boolean) = broker.certain(device.nodeId, "ps")
            override fun service(service: AgentService, action: TaskServiceAction, confirmed: Boolean) = error("unused")
        }
        val controller = TaskToolsController(wire, false, this, refreshMillis = 10)
        controller.setActive(true); yield()
        assertEquals(1, sent.size)
        controller.setActive(false); yield()
        controller.setActive(true); yield()
        assertEquals("Do not send across an undrained read", 1, sent.size)
        assertFalse(controller.state.value.verificationReady)
        assertTrue("Late answer must be consumed", broker.accept(processes()))
        assertTrue("Normal cancellation must recover after draining", broker.certain("node//test", "ps"))
        withTimeout(1_000) { while (sent.size < 2) delay(1) }
        assertEquals(2, sent.size)
        broker.accept(processes())
        val recovered = withTimeout(1_000) { controller.state.first { it.loaded && !it.loading } }
        assertTrue(recovered.verificationReady)
        assertNull(recovered.error)
        assertTrue(wire.kill(recovered.processes.last(), true))
        controller.close()
    }

    @Test fun cancelledServiceDetailsDrainBothRepliesInEitherOrder() = runBlocking<Unit> {
        for (listFirst in listOf(false, true)) {
            val broker = TaskToolsBroker { true }
            val read = launch(start = CoroutineStart.UNDISPATCHED) { broker.request(command("service").put("serviceName", "test")) }
            broker.accept(command(if (listFirst) "services" else "service").put("value", "[]"))
            read.cancelAndJoin()
            assertTrue(broker.accept(command(if (listFirst) "service" else "services").put("value", "[]")))
            assertTrue(broker.certain("node//test", "services"))
            val next = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command("services")) }
            broker.accept(command("services").put("value", "[]"))
            assertTrue(TaskToolsBroker.verifiable(next.await()))
        }
    }

    @Test fun abandonedReadBlocksNewSendUntilDrainedOrBoundedExpiry() = runBlocking<Unit> {
        var time = 0L
        var sends = 0
        val broker = TaskToolsBroker(drainTimeoutMillis = 10, nanoTime = { time }) { sends++; true }
        val read = launch(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        read.cancelAndJoin()
        assertFalse(broker.certain("node//test", "ps"))
        assertEquals("task_reply_pending", (runCatching { broker.request(command()) }.exceptionOrNull() as ControlRequestException).code)
        assertEquals(1, sends)
        time = 11_000_000
        val next = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        assertEquals(2, sends)
        broker.accept(processes())
        assertFalse("Expired drain cannot establish freshness", TaskToolsBroker.verifiable(next.await()))
    }

    @Test fun parsedNodeEventsAndSnapshotsInvalidateOnlyLostAgents() = runBlocking<Unit> {
        val offlineEvents = listOf(
            """{"action":"nodeconnect","nodeid":"node//test","conn":0}""",
            """{"action":"changenode","node":{"_id":"node//test","conn":0}}""",
            """{"action":"removenode","nodeid":"node//test"}""",
        )
        for (event in offlineEvents) {
            val broker = TaskToolsBroker { true }
            val old = broker.generation("node//test")
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(command()) } }
            val parsed = MeshControlParser.parse("""{"action":"event","event":$event}""") as MeshControlEvent.NodeUpdated
            broker.nodeEvent(parsed.event)
            assertEquals("agent_disconnected", (pending.await().exceptionOrNull() as ControlRequestException).code)
            assertNotEquals(old, broker.generation("node//test"))
        }
        val broker = TaskToolsBroker { true }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(command()) } }
        val partial = MeshControlParser.parse("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//test","name":"renamed"}}}""") as MeshControlEvent.NodeUpdated
        broker.nodeEvent(partial.event)
        assertFalse(pending.isCompleted)
        val device = MeshDevice("node//test", "mesh//test", "Test", conn = 1)
        broker.nodesSnapshot(listOf(device))
        assertFalse(pending.isCompleted)
        broker.nodesSnapshot(emptyList()) // a paged or by-id reply without this device
        assertFalse(pending.isCompleted)
        broker.nodesSnapshot(listOf(device.copy(conn = 0, connectivityKnown = true)))
        assertEquals("agent_disconnected", (pending.await().exceptionOrNull() as ControlRequestException).code)
    }

    @Test fun androidIsReadOnlyEvenAfterProbeAndMissingFeaturesMatchWebBitwiseDefault() {
        val computer = MeshDevice("node//test", "mesh//test", "Test", conn = 1, agentId = 6, rights = 8)
        assertTrue(TaskToolsProtocol.canMutate(computer, true, null))
        assertFalse(TaskToolsProtocol.canMutate(computer, true, 0x2000))
        for (rights in listOf(8, -1)) {
            val android = computer.copy(agentId = 14, rights = rights)
            assertTrue(TaskToolsProtocol.canRead(android, true))
            assertFalse(TaskToolsProtocol.canMutate(android, true, null))
            assertTrue(runCatching { TaskToolsProtocol.kill(android, true, AgentProcess(42, "test"), true) }.isFailure)
        }
    }

    @Test fun verificationDeadlineDoesNotCancelAnInFlightBrokerRead() = runBlocking<Unit> {
        var sends = 0
        lateinit var broker: TaskToolsBroker
        broker = TaskToolsBroker {
            sends++
            if (sends == 1) broker.accept(processes()) else launch { delay(1_500); broker.accept(processes()) }
            true
        }
        val wire = object : TaskToolsTransport {
            override val device = MeshDevice("node//test", "mesh//test", "Test", conn = 1, agentId = 6, rights = 8)
            override val current = true
            override suspend fun read(type: String, pid: Long?, timeoutMillis: Long) = broker.request(command(type), timeoutMillis)
            override suspend fun serviceDetails(name: String): JSONObject = error("unused")
            override fun kill(process: AgentProcess, confirmed: Boolean) = true
            override fun service(service: AgentService, action: TaskServiceAction, confirmed: Boolean) = error("unused")
        }
        val c = TaskToolsController(wire, false, this, pollMillis = 1, processDeadline = 300)
        try {
            c.kill(AgentProcess(2, "worker"), true)
            val result = withTimeout(10_000) { c.state.first { !it.mutating && it.notice != null } }
            assertEquals(ru.papajon.meshcentralmobile.R.string.tasks_still_running, result.notice)
            assertTrue(result.verificationReady)
            assertTrue(broker.certain("node//test", "ps"))
        } finally { c.close() }
    }

    @Test fun systemdObservationScansLargeSnapshotsLinearly() {
        var accesses = 0
        fun rows(stopped: Int) = object : AbstractList<AgentService>() {
            override val size = 2_000
            override fun get(index: Int): AgentService {
                accesses++
                return AgentService("unit-$index", "Unit $index", if (index < stopped) null else "RUNNING", serviceType = "systemd")
            }
        }
        val stopped = TaskToolsEvidence.systemdStoppedNames(rows(0), rows(500))
        assertEquals((0 until 500).map { "unit-$it" }.toSet(), stopped)
        assertTrue("Repeated per-row scans: $accesses element visits", accesses < 40_000)
    }
}
