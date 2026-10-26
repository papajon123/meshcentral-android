package ru.papajon.meshcentralmobile.mesh.control

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParityReplyRegressionTest {
    @Test fun toastDenialAfterOkIsReportedEvenAfterRequestReturned() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val failures = mutableListOf<ControlRequestFailure>()
        val broker = ControlRequestBroker(onLateFailure = { failures.add(it) }) { sent.add(it); true }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            broker.request(JSONObject("""{"action":"toast","nodeids":["node//example"],"title":"Title","msg":"Message"}"""), "toast", "node//example")
        }
        val id = sent.single().getString("responseid")
        assertTrue(broker.accept(JSONObject().put("action", "toast").put("responseid", id).put("result", "ok")))
        result.await()
        assertTrue(broker.accept(JSONObject().put("action", "toast").put("responseid", id).put("result", "Access Denied")))
        assertEquals(listOf(ControlRequestFailure("node//example", "toast", "Access Denied")), failures)
        broker.disconnected()
        assertFalse(broker.accept(JSONObject().put("action", "toast").put("responseid", id).put("result", "Access Denied")))
    }
    @Test fun routingDenialWithOnlyResponseIdFailsCpuRequestImmediately() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            broker.request(JSONObject("""{"action":"msg","type":"cpuinfo","nodeid":"node//example"}"""), "msg", "node//example", 500)
        } }
        assertTrue(broker.accept(JSONObject().put("action", "msg").put("result", "Unable to route").put("responseid", sent.single().getString("responseid"))))
        assertEquals("Unable to route", result.await().exceptionOrNull()?.message)
    }
    @Test fun softwareCanSendANewRequestAfterTimeout() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = ControlRequestBroker { sent.add(it); true }
        runCatching { broker.request(JSONObject("""{"action":"software","type":"installedapps","nodeid":"node//example"}"""), "software", "node//example", 1) }
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            broker.request(JSONObject("""{"action":"software","type":"installedapps","nodeid":"node//example"}"""), "software", "node//example", 500)
        } }
        assertEquals(2, sent.size)
        assertTrue(broker.accept(JSONObject("""{"action":"software","nodeid":"node//example","value":"[]"}""")))
        assertTrue(result.await().isSuccess)
    }
    @Test fun commandRejectionRetainsActualServerReason() = runBlocking {
        for (reason in listOf("Access denied", "Agent not connected", "Invalid command type")) {
            val sent = mutableListOf<JSONObject>()
            val broker = ControlRequestBroker { sent.add(it); true }
            val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                broker.request(JSONObject("""{"action":"runcommands","reply":true}"""), "msg", "node//example", 500)
            } }
            broker.accept(JSONObject().put("action", "runcommands").put("responseid", sent.single().getString("responseid")).put("result", reason))
            assertEquals(reason, result.await().exceptionOrNull()?.message)
        }
    }
}
