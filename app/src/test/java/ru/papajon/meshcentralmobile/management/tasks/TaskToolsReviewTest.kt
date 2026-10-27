package ru.papajon.meshcentralmobile.management.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

class TaskToolsReviewTest {
    private val target = AgentProcess(42, "sleep")
    private val survivors = listOf(AgentProcess(1, "init"), AgentProcess(2, "worker"), AgentProcess(3, "other"))
    private fun reply(rows: List<AgentProcess>) = JSONObject().put("action", "msg").put("type", "ps").put("nodeid", "node//test")
        .put("value", JSONObject().apply { rows.forEach { put(it.pid.toString(), JSONObject().put("cmd", it.name)) } }.toString())
    private inner class Wire : TaskToolsTransport {
        override var device: MeshDevice? = MeshDevice("node//test", "mesh//test", "Test", conn = 1, agentId = 6, rights = 8)
        override var current = true
        override var features2: Long? = null
        override var generation = 0L
        var calls = 0
        var sends = 0
        var sendResult = true
        var after = survivors
        var before = survivors + target
        var onRead: suspend (Int) -> Unit = {}
        override suspend fun read(type: String, pid: Long?, timeoutMillis: Long): JSONObject { onRead(++calls); return reply(if (sends == 0) before else after) }
        override suspend fun serviceDetails(name: String): JSONObject = error("unused")
        override fun kill(process: AgentProcess, confirmed: Boolean): Boolean {
            TaskToolsProtocol.kill(device!!, current, process, confirmed, features2); sends++; return sendResult
        }
        override fun service(service: AgentService, action: TaskServiceAction, confirmed: Boolean) = error("unused")
    }
    private suspend fun outcome(c: TaskToolsController) = withTimeout(3_000) { c.state.first { !it.mutating && (it.notice != null || it.error != null) } }

    @Test fun emptyAndMinoritySnapshotsCannotConfirmTermination() = runBlocking {
        for (after in listOf(emptyList(), survivors.take(1), survivors.take(2))) {
            val wire = Wire().apply { this.after = after }; val c = TaskToolsController(wire, false, this, 1, 100)
            try { c.kill(target, true); assertEquals(R.string.tasks_unverified, outcome(c).notice); assertEquals(1, wire.sends) }
            finally { c.close() }
        }
    }
    @Test fun renamedPidConfirmsTerminationOnlyOnWindows() = runBlocking {
        for (agent in listOf(3, 6, 11)) {
            val wire = Wire().apply { device = device?.copy(agentId = agent); after = survivors + target.copy(name = "replacement") }
            val c = TaskToolsController(wire, false, this, 1, 100)
            try { c.kill(target, true); assertEquals(if (agent == 3) R.string.tasks_killed else R.string.tasks_unverified, outcome(c).notice) }
            finally { c.close() }
        }
    }
    @Test fun leavingDuringPreflightDoesNotSayCommandWasSent() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val wire = Wire().apply { onRead = { gate.await() } }
        val c = TaskToolsController(wire, false, this)
        try {
            c.setActive(true); yield(); c.kill(target, true); yield(); c.setActive(false); yield()
            assertEquals(0, wire.sends); assertNull(c.state.value.notice); assertFalse(c.state.value.mutating)
        } finally { c.close() }
    }
    @Test fun confirmationQueuesBehindRefreshInsteadOfDroppingTheAction() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val wire = Wire().apply { onRead = { if (it == 1) gate.await() } }
        val c = TaskToolsController(wire, false, this, 1, 100)
        try {
            c.refresh(); yield(); c.kill(target, true); c.kill(target, true)
            assertTrue(c.state.value.mutating); assertEquals(0, wire.sends)
            gate.complete(Unit); assertEquals(R.string.tasks_killed, outcome(c).notice); assertEquals(1, wire.sends)
        } finally { c.close() }
    }
    @Test fun confirmationPausesPeriodicRefresh() = runBlocking {
        val wire = Wire(); val c = TaskToolsController(wire, false, this, refreshMillis = 10)
        try {
            c.setActive(true); withTimeout(1_000) { c.state.first { it.loaded && !it.loading } }
            c.setConfirmationOpen(true); val count = wire.calls; delay(40); assertEquals(count, wire.calls)
            c.setConfirmationOpen(false); withTimeout(1_000) { while (wire.calls == count) delay(1) }
        } finally { c.close() }
    }
    @Test fun featureFlagIsRecheckedBeforeSendingIncludingFullAdmin() = runBlocking {
        val wire = Wire().apply { device = device!!.copy(rights = MeshRights.FULL); onRead = { features2 = 0x2000 } }
        val c = TaskToolsController(wire, false, this)
        try { c.kill(target, true); assertEquals(R.string.tasks_denied, outcome(c).error); assertEquals(0, wire.sends) } finally { c.close() }
    }
    @Test fun sendingAndValidationFailuresHaveActionMessagesWithoutSentNotice() = runBlocking {
        for (invalid in listOf(false, true)) {
            val chosen = if (invalid) target.copy(name = "bad|name") else target
            val wire = Wire().apply { before = survivors + chosen; sendResult = false }
            val c = TaskToolsController(wire, false, this)
            try {
                c.kill(chosen, true); val result = outcome(c)
                assertEquals(if (invalid) R.string.tasks_invalid_target else R.string.tasks_send_failed, result.error)
                assertNull(result.notice)
            } finally { c.close() }
        }
    }
    @Test fun agentRestartDuringVerificationCannotConfirmAnOldAction() = runBlocking {
        val wire = Wire().apply { onRead = { if (it > 1) generation++ } }; val c = TaskToolsController(wire, false, this, 1, 100)
        try { c.kill(target, true); assertEquals(R.string.tasks_unverified, outcome(c).notice); assertEquals(R.string.tasks_offline, c.state.value.error) } finally { c.close() }
    }
    @Test fun retryUsesRealBrokerAndRemainsDisplayOnlyUntilAgentReconnect() = runBlocking {
        val wire = Wire(); var respond = false; lateinit var broker: TaskToolsBroker
        broker = TaskToolsBroker { command -> if (respond) broker.accept(reply(survivors + target)); true }
        val transport = object : TaskToolsTransport by wire {
            override val generation get() = broker.generation("node//test")
            override suspend fun read(type: String, pid: Long?, timeoutMillis: Long) = broker.request(TaskToolsProtocol.read("node//test", type), 15)
        }
        val c = TaskToolsController(transport, false, this)
        try {
            c.refresh(); withTimeout(1_000) { c.state.first { !it.loading && it.error != null } }
            respond = true; c.refresh(); withTimeout(1_000) { c.state.first { it.loaded && !it.loading } }
            assertFalse(c.state.value.verificationReady); assertEquals(R.string.tasks_uncertain_reply, c.state.value.error)
            c.kill(target, true); assertEquals(R.string.tasks_uncertain_reply, outcome(c).error); assertEquals(0, wire.sends)
            broker.nodeDisconnected("node//test"); c.refresh()
            withTimeout(1_000) { c.state.first { it.verificationReady && !it.loading } }; Unit
        } finally { c.close() }
    }
    @Test fun agentDisconnectReleasesOnlyItsRequestsAndChangesGeneration() = runBlocking {
        val broker = TaskToolsBroker { true }; val before = broker.generation("node//a")
        val a = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(TaskToolsProtocol.read("node//a", "ps")) } }
        val b = async(start = CoroutineStart.UNDISPATCHED) { broker.request(TaskToolsProtocol.read("node//b", "ps")) }
        broker.nodeDisconnected("node//a")
        assertEquals("agent_disconnected", (a.await().exceptionOrNull() as ControlRequestException).code)
        assertNotEquals(before, broker.generation("node//a")); assertFalse(b.isCompleted)
        broker.accept(reply(survivors).put("nodeid", "node//b")); assertTrue(TaskToolsBroker.verifiable(b.await()))
    }
    @Test fun routingFailureIsNotMisreportedAsPermissionDenial() = runBlocking {
        for ((message, code) in listOf("Unable to route" to "offline", "Access denied" to "denied", "Unexpected failure" to "server_rejected")) {
            var id = ""; val broker = TaskToolsBroker { id = it.getString("responseid"); true }
            val read = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(TaskToolsProtocol.read("node//test", "ps")) } }
            broker.accept(JSONObject().put("action", "msg").put("responseid", id).put("result", message))
            assertEquals(code, (read.await().exceptionOrNull() as ControlRequestException).code)
        }
    }
    @Test fun pidZeroIsNeitherDisplayedNorRequestedNorKilled() = runBlocking {
        assertTrue(TaskToolsProtocol.processes(reply(listOf(AgentProcess(0, "idle")))).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.kill(Wire().device!!, true, AgentProcess(0, "idle"), true) }
        var sent = false; val broker = TaskToolsBroker { sent = true; true }
        assertTrue(runCatching { broker.request(JSONObject().put("action", "msg").put("nodeid", "node//test").put("type", "psinfo").put("pid", 0)) }.isFailure)
        assertFalse(sent)
    }
    @Test fun systemdAbsenceNeedsAnIntactListAndRunningWitnesses() {
        fun unit(name: String, state: String? = "RUNNING") = AgentService(name, name, state, serviceType = "systemd")
        val before = listOf(unit("target"), unit("a"), unit("b"), unit("unknown", null))
        val stopped = unit("target", null)
        val after = listOf(stopped, unit("a"), unit("b"), unit("unknown", null))
        assertEquals(setOf("target"), TaskToolsEvidence.systemdStoppedNames(before, after))
        assertTrue(TaskToolsEvidence.systemdStoppedNames(before, after.map { it.copy(state = null) }).isEmpty())
        assertTrue(TaskToolsEvidence.systemdStoppedNames(before, listOf(stopped)).isEmpty())
        assertTrue(TaskToolsEvidence.systemdStoppedNames(listOf(unit("target")), listOf(stopped)).isEmpty())
    }
    @Test fun linuxCriticalServicesIncludeAgentNetworkAndSystemdUnits() {
        listOf("sshd", "ssh.service", "NetworkManager", "systemd-networkd.service", "systemd-logind", "meshagent", "meshagent@work").forEach {
            assertTrue(it, TaskToolsProtocol.critical(AgentService(it, it)))
        }
    }
}
