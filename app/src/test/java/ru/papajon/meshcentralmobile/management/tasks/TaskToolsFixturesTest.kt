package ru.papajon.meshcentralmobile.management.tasks

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskToolsFixturesTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("task-tools/$name.json")!!.readText().removePrefix("\uFEFF"))
    @Test fun recordedLinuxProcessShapeParsesWithoutDiscardingRows() {
        val rows = TaskToolsProtocol.processes(fixture("linux-ps"))
        assertEquals(8, rows.size)
        assertTrue(rows.all { it.user == "fixture" })
    }
    @Test fun recordedLinuxServiceTopLevelStateRemainsKnown() {
        val rows = TaskToolsProtocol.services(fixture("linux-services"))
        assertEquals(8, rows.size)
        assertTrue(rows.any { it.running == true })
        assertTrue(rows.any { it.running == null })
        assertFalse(rows.any { it.running == false })
    }
    @Test fun windowsFormatHasPidUserPathAndServiceState() {
        assertTrue(fixture("windows-ps").get("value") is String)
        assertTrue(fixture("windows-services").get("value") is String)
        val rows = TaskToolsProtocol.processes(fixture("windows-ps"))
        assertEquals(listOf(4L, 120L), rows.map { it.pid })
        assertEquals("C:\\Fixture\\fixture.exe", rows.last().command)
        val services = TaskToolsProtocol.services(fixture("windows-services"))
        assertEquals(true, services.first().running)
        assertEquals(false, services.last().running)
        assertEquals("AUTO_START", services.first().startType)
    }
    @Test fun linuxPsHeaderIsIgnoredButCannotProveAnEmptySnapshot() {
        val header = JSONObject().put("pid", "PID").put("user", "USER").put("cmd", "fixture header text")
        val data = JSONObject().put("PID", header).put("42", JSONObject().put("cmd", "sleep").put("pid", "42"))
        assertEquals(listOf(42L), TaskToolsProtocol.processes(JSONObject().put("value", data)).map { it.pid })
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.processes(JSONObject().put("value", JSONObject().put("PID", header))) }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.processes(JSONObject().put("value", data.put("bad", JSONObject().put("cmd", "sleep")))) }
    }
    @Test fun linuxDuplicateServicesCollapseOnlyWhenTheyAgree() {
        val row = JSONObject().put("name", "fixture").put("serviceType", "systemd").put("state", "RUNNING")
        val data = org.json.JSONArray().put(row).put(JSONObject(row.toString()))
        assertEquals(1, TaskToolsProtocol.services(JSONObject().put("value", data)).size)
        data.put(JSONObject(row.toString()).put("state", "STOPPED"))
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.services(JSONObject().put("value", data)) }
    }
    @Test fun serviceDetailsRequireMatchingNameAndKeepStartupType() {
        val reply = JSONObject().put("value", JSONObject().put("name", "fixture").put("status", "").put("startType", "AUTO_START"))
        val service = TaskToolsProtocol.serviceDetails(reply, "fixture")
        assertEquals("AUTO_START", service.startType)
        assertNull(service.running)
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.serviceDetails(reply, "other") }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.serviceDetails(reply.put("error", "failure"), "fixture") }
    }
}
