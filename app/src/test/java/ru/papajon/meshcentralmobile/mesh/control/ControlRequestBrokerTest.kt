package ru.papajon.meshcentralmobile.mesh.control

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControlRequestBrokerTest {
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
