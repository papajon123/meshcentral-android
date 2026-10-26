package ru.papajon.meshcentralmobile.mesh.control

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControlRequestBrokerTest {
    @Test fun timedOutSoftwareDoesNotRequireReconnectAndIgnoresReplyWithoutPendingRequest() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        assertTrue(runCatching { broker.request(JSONObject().put("action", "software"), "software", "node//a", 1) }.isFailure)
        val retry = runCatching { broker.request(JSONObject().put("action", "software"), "software", "node//a", 1) }
        assertTrue(retry.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(2, sent.size)
        assertFalse(broker.accept(JSONObject().put("action", "software").put("nodeid", "node//a").put("value", "[]")))
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "software"), "software", "node//a") }
        assertTrue(broker.accept(JSONObject().put("action", "software").put("nodeid", "node//a").put("value", "[]")))
        fresh.await()
        Unit
    }
    @Test fun runCommandDenialIsAnErrorNotSuccessfulOutput() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(JSONObject().put("action", "runcommands").put("reply", true), "msg", "node//a") } }
        assertTrue(broker.accept(JSONObject().put("action", "runcommands").put("responseid", sent.single().getString("responseid")).put("result", "Access denied")))
        assertEquals("Access denied", (job.await().exceptionOrNull() as ControlRequestException).serverReason)
    }
    @Test fun runCommandWaitsForTypedOutputAndRejectsWrongNode() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val job = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "runcommands").put("reply", true), "msg", "node//a") }
        val id = sent.single().getString("responseid")
        assertFalse(broker.accept(JSONObject().put("action", "runcommands").put("responseid", id).put("result", "OK")))
        assertFalse(broker.accept(JSONObject().put("action", "msg").put("type", "console").put("responseid", id).put("nodeid", "node//a")))
        assertFalse(broker.accept(JSONObject().put("action", "msg").put("type", "runcommands").put("responseid", id).put("nodeid", "node//b")))
        assertTrue(broker.accept(JSONObject().put("action", "msg").put("type", "runcommands").put("responseid", id).put("nodeid", "node//a").put("result", "hello")))
        assertEquals("hello", job.await().getString("result"))
    }
    @Test fun softwareOmitsResponseIdAndMatchesOnlyTheRequestedNode() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val job = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "software").put("type", "installedapps"), "software", "node//a") }
        assertFalse(sent.single().has("responseid"))
        assertFalse(broker.accept(JSONObject().put("action", "software").put("nodeid", "node//b").put("value", "[]")))
        assertTrue(broker.accept(JSONObject().put("action", "software").put("nodeid", "node//a").put("value", "[]")))
        assertEquals("[]", job.await().getString("value"))
    }
    @Test fun reversedRepliesStayWithTheirDeviceAndWrongNodeCannotSatisfyARequest() = runBlocking {
        val sent = CopyOnWriteArrayList<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val first = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "getsysinfo"), "getsysinfo", "node//a") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "getsysinfo"), "getsysinfo", "node//b") }
        assertFalse(broker.accept(JSONObject().put("action", "getsysinfo").put("nodeid", "node//b").put("tag", sent[0].getString("tag"))))
        assertTrue(broker.accept(JSONObject().put("action", "getsysinfo").put("nodeid", "node//b").put("tag", sent[1].getString("tag"))))
        assertTrue(broker.accept(JSONObject().put("action", "getsysinfo").put("nodeid", "node//a").put("tag", sent[0].getString("tag"))))
        assertEquals("node//a", first.await().getString("nodeid"))
        assertEquals("node//b", second.await().getString("nodeid"))
        assertEquals(0, broker.pendingCount())
    }

    @Test fun onlyAuditedReadsAcceptUntaggedResponses() = runBlocking {
        val sent = CopyOnWriteArrayList<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val read = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "getnetworkinfo"), "getnetworkinfo", "node//a") }
        assertFalse(broker.accept(JSONObject().put("action", "getnetworkinfo").put("nodeid", "node//b")))
        assertTrue(broker.accept(JSONObject().put("action", "getnetworkinfo").put("nodeid", "node//a")))
        read.await()
        val power = async(start = CoroutineStart.UNDISPATCHED) { broker.request(JSONObject().put("action", "poweraction"), "poweraction") }
        assertFalse(broker.accept(JSONObject().put("action", "poweraction").put("result", "ok")))
        assertTrue(broker.accept(JSONObject().put("action", "poweraction").put("responseid", sent.last().getString("responseid")).put("result", "ok")))
        assertEquals("ok", power.await().getString("result"))
    }

    @Test fun pluginNameIsBoundToTheRequestAndDisconnectFailsClosed() = runBlocking {
        val sent = CopyOnWriteArrayList<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { broker.request(JSONObject().put("action", "plugin").put("plugin", "mobilecompanion"), "plugin") }
        }
        assertFalse(broker.accept(JSONObject().put("action", "plugin").put("plugin", "other").put("requestId", sent.single().getString("requestId"))))
        broker.disconnected()
        assertEquals("disconnected", (request.await().exceptionOrNull() as ControlRequestException).code)
        assertEquals(0, broker.pendingCount())
    }
}
