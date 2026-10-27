package ru.papajon.meshcentralmobile.mesh.control

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceAdminBrokerTest {
    @Test fun isolatedSnapshotsKeepCorrelationAndRemainRecognizableAfterCompletion() = runBlocking {
        val sent = CompletableDeferred<JSONObject>()
        val broker = ControlRequestBroker { sent.complete(it); true }
        val task = async { broker.request(JSONObject().put("action", "nodes"), "nodes", isolatedSnapshot = true) }
        val request = sent.await()
        val reply = JSONObject().put("action", "nodes").put("responseid", request.getString("responseid")).put("nodes", JSONObject())
        assertTrue(ControlRequestBroker.isIsolatedSnapshot(reply))
        assertTrue(broker.accept(reply))
        assertEquals(0, task.await().getJSONObject("nodes").length())
        assertTrue(ControlRequestBroker.isIsolatedSnapshot(reply))
        assertFalse(ControlRequestBroker.isIsolatedSnapshot(JSONObject().put("action", "nodes").put("responseid", "android-nodes")))
    }
    @Test fun queuedNotesReadCannotBypassTimeoutQuarantine() = runBlocking {
        supervisorScope {
            val sent = CompletableDeferred<Unit>()
            var count = 0
            val broker = ControlRequestBroker { count++; sent.complete(Unit); true }
            val first = async { broker.request(JSONObject().put("action", "getNotes"), "getNotes", "node//a", 100) }
            sent.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "getNotes"), "getNotes", "node//a", 1000) }
            try { first.await(); fail() } catch (_: TimeoutCancellationException) { }
            try { second.await(); fail() } catch (error: ControlRequestException) { assertEquals("reconnect_required", error.code) }
            assertEquals(1, count)
        }
    }
    @Test fun notesCorrelateByIdWithoutResponseId() = runBlocking {
        val sent = CompletableDeferred<JSONObject>()
        val broker = ControlRequestBroker { sent.complete(it); true }
        val task = async { broker.request(JSONObject().put("action", "getNotes").put("id", "node//a"), "getNotes", "node//a") }
        sent.await()
        assertFalse(broker.accept(JSONObject("""{"action":"getNotes","id":"node//b","notes":"wrong"}""")))
        assertTrue(broker.accept(JSONObject("""{"action":"getNotes","id":"node//a","notes":"right"}""")))
        assertEquals("right", task.await().getString("notes"))
    }
    @Test fun lateNotesCannotVerifyAnotherRequestAfterTimeout() = runBlocking {
        var count = 0
        val broker = ControlRequestBroker { count++; true }
        try { broker.request(JSONObject().put("action", "getNotes"), "getNotes", "node//a", 20); fail() }
        catch (_: TimeoutCancellationException) { }
        try { broker.request(JSONObject().put("action", "getNotes"), "getNotes", "node//a", 20); fail() }
        catch (error: ControlRequestException) { assertEquals("reconnect_required", error.code) }
        assertEquals(1, count)
        broker.disconnected()
        try { broker.request(JSONObject().put("action", "getNotes"), "getNotes", "node//a", 20); fail() }
        catch (_: TimeoutCancellationException) { }
        assertEquals(2, count)
    }
    @Test fun correlatedMutationErrorIsNotSuccess() = runBlocking {
        supervisorScope {
            val sent = CompletableDeferred<JSONObject>()
            val broker = ControlRequestBroker { sent.complete(it); true }
            val task = async { broker.request(JSONObject().put("action", "changeDeviceMesh"), "changeDeviceMesh", "node//a") }
            val request = sent.await()
            broker.accept(JSONObject().put("action", "changeDeviceMesh").put("responseid", request.getString("responseid")).put("result", "Permission denied"))
            try { task.await(); fail() } catch (error: ControlRequestException) { assertEquals("server_rejected", error.code) }
        }
    }
}
