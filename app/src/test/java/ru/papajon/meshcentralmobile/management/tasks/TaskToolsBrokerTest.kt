package ru.papajon.meshcentralmobile.management.tasks

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

class TaskToolsBrokerTest {
    private fun command(type: String = "ps") = JSONObject().put("action", "msg").put("nodeid", "node//test").put("type", type)
    @Test fun rejectsWrongNodeTypeAndRoutingAcknowledgement() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val broker = TaskToolsBroker { sent.add(it); true }
        val result = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        assertFalse(broker.accept(command().put("nodeid", "node//other")))
        assertFalse(broker.accept(command("services")))
        assertTrue(broker.accept(JSONObject().put("action", "msg").put("responseid", sent.single().getString("responseid")).put("result", "OK")))
        assertFalse(result.isCompleted)
        assertTrue(broker.accept(command().put("value", "{}")))
        assertEquals("{}", result.await().getString("value"))
    }
    @Test fun timedOutSlotCanBeRetriedButLateReplyCannotProveFreshness() = runBlocking {
        var sends = 0
        val broker = TaskToolsBroker { sends++; true }
        assertTrue(runCatching { broker.request(command(), 1) }.exceptionOrNull() is TimeoutCancellationException)
        val next = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        assertEquals(2, sends)
        assertTrue(broker.accept(command().put("value", "{}").put("_taskToolsVerifiable", true)))
        assertFalse(TaskToolsBroker.verifiable(next.await()))
        broker.nodeDisconnected("node//test")
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        broker.accept(command().put("value", "{}"))
        assertTrue(TaskToolsBroker.verifiable(fresh.await()))
    }
    @Test fun disconnectClearsQuarantineAndFailsPendingRead() = runBlocking {
        val broker = TaskToolsBroker { true }
        val read = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(command()) } }
        broker.disconnected()
        assertEquals("disconnected", (read.await().exceptionOrNull() as ControlRequestException).code)
        val next = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command()) }
        broker.accept(command().put("value", "{}"))
        next.await()
        Unit
    }
    @Test fun detailsMustMatchPid() = runBlocking {
        val broker = TaskToolsBroker { true }
        val read = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command("psinfo").put("pid", 42)) }
        assertFalse(broker.accept(command("psinfo").put("pid", 43).put("value", JSONObject())))
        assertTrue(broker.accept(command("psinfo").put("pid", 42).put("value", JSONObject())))
        read.await()
        Unit
    }
    @Test fun serviceDetailsDrainFallthroughInEitherOrderBeforeReturning() = runBlocking {
        for (listFirst in listOf(true, false)) {
            val broker = TaskToolsBroker { true }
            val read = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command("service").put("serviceName", "fixture")) }
            val detail = command("service").put("value", JSONObject().put("name", "fixture"))
            val list = command("services").put("value", "[]")
            assertTrue(broker.accept(if (listFirst) list else detail))
            assertFalse(read.isCompleted)
            assertTrue(broker.accept(if (listFirst) detail else list))
            assertEquals("service", read.await().getString("type"))
            val fresh = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command("services")) }
            broker.accept(list)
            fresh.await()
        }
    }
    @Test fun lateServiceFallthroughCannotBecomeNewVerificationSnapshot() = runBlocking {
        val broker = TaskToolsBroker { true }
        val read = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.request(command("service").put("serviceName", "fixture"), 10) } }
        broker.accept(command("service").put("value", JSONObject().put("name", "fixture")))
        assertTrue(read.await().exceptionOrNull() is TimeoutCancellationException)
        val next = async(start = CoroutineStart.UNDISPATCHED) { broker.request(command("services")) }
        assertTrue(broker.accept(command("services").put("value", "[]")))
        assertFalse(TaskToolsBroker.verifiable(next.await()))
    }
}
