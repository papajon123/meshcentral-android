package ru.papajon.meshcentralmobile.management.tasks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice

class TaskToolsControllerTest {
    private companion object {
        const val SURVIVORS = """{"1":{"cmd":"init"},"10":{"cmd":"worker"},"11":{"cmd":"other"}}"""
        const val BASELINE = """{"1":{"cmd":"init"},"10":{"cmd":"worker"},"11":{"cmd":"other"},"42":{"cmd":"sleep","user":"fixture"}}"""
    }
    private class Fake : TaskToolsTransport {
        override var device: MeshDevice? = MeshDevice("node//fixture", "mesh//fixture", "Fixture", conn = 1, agentId = 6, rights = 8)
        override var current = true
        override var features2: Long? = null
        override var generation = 0L
        var sendResult = true
        var beforeKill = BASELINE
        var kills = 0
        var changes = 0
        var reads = 0
        var afterKill = SURVIVORS
        var onRead: suspend (Int) -> Unit = {}
        var services = "[{\"name\":\"fixture\",\"displayName\":\"Fixture\",\"status\":{\"state\":\"RUNNING\",\"pid\":10}}]"
        override suspend fun read(type: String, pid: Long?, timeoutMillis: Long): JSONObject {
            onRead(++reads)
            return JSONObject().put("action", "msg").put("nodeid", "node//fixture").put("type", type)
                .put("value", if (type == "services") services else if (kills == 0) beforeKill else afterKill)
        }
        override fun kill(process: AgentProcess, confirmed: Boolean): Boolean { TaskToolsProtocol.kill(device!!, current, process, confirmed, features2); kills++; return sendResult }
        override suspend fun serviceDetails(name: String) = JSONObject().put("value", JSONObject().put("name", name).put("startType", "AUTO_START"))
        override fun service(service: AgentService, action: TaskServiceAction, confirmed: Boolean): Boolean { check(confirmed); changes++; return sendResult }
    }
    private val process = AgentProcess(42, "sleep", "fixture")
    private suspend fun finished(c: TaskToolsController): TaskToolsState = withTimeout(3_000) {
        c.state.first { !it.mutating && (it.notice != null || it.error != null) }
    }
    @Test fun disappearanceIsRequiredForKillSuccess() = runBlocking {
        val fake = Fake(); val c = TaskToolsController(fake, false, this, 1, 100)
        try { c.kill(process, true); assertEquals(R.string.tasks_killed, finished(c).notice); assertEquals(1, fake.kills); assertTrue(fake.reads >= 2) }
        finally { c.close() }
    }
    @Test fun stillPresentAfterDeadlineIsNotSuccess() = runBlocking {
        val fake = Fake().apply { afterKill = BASELINE }
        val c = TaskToolsController(fake, false, this, 30, 110)
        try { c.kill(process, true); assertEquals(R.string.tasks_still_running, finished(c).notice) } finally { c.close() }
    }
    @Test fun malformedReplyAfterSendNeverClaimsSuccess() = runBlocking {
        val fake = Fake().apply { afterKill = "invalid" }
        val c = TaskToolsController(fake, false, this, 1, 100)
        try { c.kill(process, true); val end = finished(c); assertEquals(R.string.tasks_unverified, end.notice); assertNotNull(end.error) } finally { c.close() }
    }
    @Test fun noPostCommandReplyDoesNotClaimProcessStillRunning() = runBlocking {
        val fake = Fake().apply { onRead = { if (it > 1) withTimeout(20) { awaitCancellation() } } }
        val c = TaskToolsController(fake, false, this, 1, 80)
        try { c.kill(process, true); assertEquals(R.string.tasks_unverified, finished(c).notice) } finally { c.close() }
    }
    @Test fun missingConfirmationDoesNotSendOrRead() = runBlocking {
        val fake = Fake(); val c = TaskToolsController(fake, false, this)
        try { c.kill(process, false); delay(10); assertEquals(0, fake.kills); assertEquals(0, fake.reads) } finally { c.close() }
    }
    @Test fun revokedRightsDuringPreflightPreventMutation() = runBlocking {
        val fake = Fake().apply { onRead = { device = device!!.copy(rights = 264) } }
        val c = TaskToolsController(fake, false, this)
        try { c.kill(process, true); assertEquals(R.string.tasks_denied, finished(c).error); assertEquals(0, fake.kills) } finally { c.close() }
    }
    @Test fun offlineAndReadOnlyRejectActions() = runBlocking {
        for (rights in listOf(256, 264, 4104, 65544, 0)) {
            val fake = Fake().apply { device = device!!.copy(rights = rights) }
            val c = TaskToolsController(fake, false, this)
            try { c.kill(process, true); assertEquals(R.string.tasks_denied, finished(c).error); assertEquals(0, fake.kills) } finally { c.close() }
        }
        val fake = Fake().apply { current = false }; val c = TaskToolsController(fake, false, this)
        try { c.kill(process, true); assertEquals(R.string.tasks_offline, finished(c).error); assertEquals(0, fake.kills) } finally { c.close() }
    }
    @Test fun pidReusedWithDifferentNameIsNotKilled() = runBlocking {
        val fake = Fake(); val c = TaskToolsController(fake, false, this)
        try { c.kill(process.copy(name = "other"), true); assertEquals(R.string.tasks_changed, finished(c).error); assertEquals(0, fake.kills) } finally { c.close() }
    }
    @Test fun restartNeedsObservedTransitionOrChangedPid() {
        val before = AgentService("fixture", "Fixture", "RUNNING", pid = 10)
        assertFalse(TaskToolsController.serviceVerified(TaskServiceAction.Restart, before, before, false))
        assertTrue(TaskToolsController.serviceVerified(TaskServiceAction.Restart, before, before.copy(pid = 11), false))
        assertTrue(TaskToolsController.serviceVerified(TaskServiceAction.Restart, before, before, true))
        assertFalse(TaskToolsController.serviceVerified(TaskServiceAction.Restart, before, before.copy(state = null), true))
        assertFalse(TaskToolsController.serviceVerified(TaskServiceAction.Stop, before, null, false))
    }
    @Test fun unchangingRunningServiceDoesNotConfirmRestart() = runBlocking {
        val fake = Fake(); val c = TaskToolsController(fake, true, this, 1, serviceDeadline = 80)
        try { c.service(AgentService("fixture", "Fixture"), TaskServiceAction.Restart, true)
            assertEquals(R.string.tasks_service_unverified, finished(c).notice); assertEquals(1, fake.changes)
        } finally { c.close() }
    }
    @Test fun unsupportedServicesHaveExplicitErrorAndRetry() = runBlocking {
        val fake = Fake().apply { onRead = { withTimeout(1) { delay(20) } } }
        val c = TaskToolsController(fake, true, this)
        try { c.refresh(); withTimeout(1_000) { c.state.first { it.error != null } }; assertEquals(R.string.tasks_unsupported, c.state.value.error)
            fake.onRead = {}; c.refresh(); withTimeout(1_000) { c.state.first { it.loaded } }; assertNull(c.state.value.error)
        } finally { c.close() }
    }
    @Test fun leavingScreenCancelsRefresh() = runBlocking {
        val fake = Fake().apply { onRead = { delay(60_000) } }; val c = TaskToolsController(fake, false, this)
        try { c.setActive(true); yield(); c.setActive(false); yield(); assertFalse(c.state.value.loading); assertEquals(1, fake.reads) }
        finally { c.close() }
    }
    @Test fun serviceDetailsEnrichStartupWithoutChangingObservedState() = runBlocking {
        val fake = Fake(); val c = TaskToolsController(fake, true, this)
        try {
            c.refresh(); withTimeout(1_000) { c.state.first { it.loaded && !it.loading } }
            c.serviceDetails(c.state.value.services.single())
            withTimeout(1_000) { c.state.first { it.serviceDetails != null && !it.detailsLoading } }
            assertEquals("AUTO_START", c.state.value.serviceDetails!!.startType)
            assertEquals(true, c.state.value.services.single().running)
            c.dismissDetails(); c.refresh()
            withTimeout(1_000) { c.state.first { !it.loading } }
            assertEquals("AUTO_START", c.state.value.services.single().startType)
        } finally { c.close() }
    }
    @Test fun systemdStartStopAndObservedRestartAreVerifiedThroughTheController() = runBlocking {
        val running = """[{"name":"fixture","serviceType":"systemd","state":"RUNNING"},{"name":"a","serviceType":"systemd","state":"RUNNING"},{"name":"b","serviceType":"systemd","state":"RUNNING"}]"""
        val stopped = """[{"name":"fixture","serviceType":"systemd"},{"name":"a","serviceType":"systemd","state":"RUNNING"},{"name":"b","serviceType":"systemd","state":"RUNNING"}]"""
        for (action in TaskServiceAction.entries) {
            val fake = Fake().apply {
                services = if (action == TaskServiceAction.Start) stopped else running
                onRead = { count -> if (changes > 0) services = if (action == TaskServiceAction.Stop || action == TaskServiceAction.Restart && count == 2) stopped else running }
            }
            val c = TaskToolsController(fake, true, this, 1, serviceDeadline = 500)
            try {
                c.service(AgentService("fixture", "fixture", serviceType = "systemd"), action, true)
                assertEquals(action.name, R.string.tasks_service_verified, finished(c).notice)
                assertEquals(1, fake.changes)
                if (action == TaskServiceAction.Stop) assertTrue(c.state.value.services.first().observedNotRunning)
            } finally { c.close() }
        }
    }
    @Test fun failedSystemdRunningTableCannotVerifyAStop() = runBlocking {
        val fake = Fake().apply {
            services = """[{"name":"fixture","serviceType":"systemd","state":"RUNNING"},{"name":"a","serviceType":"systemd","state":"RUNNING"}]"""
            onRead = { if (changes > 0) services = """[{"name":"fixture","serviceType":"systemd"},{"name":"a","serviceType":"systemd"}]""" }
        }
        val c = TaskToolsController(fake, true, this, 10, serviceDeadline = 100)
        try {
            c.service(AgentService("fixture", "fixture", serviceType = "systemd"), TaskServiceAction.Stop, true)
            assertEquals(R.string.tasks_service_unverified, finished(c).notice)
            assertTrue(c.state.value.services.all { it.running == null })
        } finally { c.close() }
    }
}
