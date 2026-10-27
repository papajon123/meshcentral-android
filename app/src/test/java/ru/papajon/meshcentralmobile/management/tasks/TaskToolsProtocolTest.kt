package ru.papajon.meshcentralmobile.management.tasks

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

class TaskToolsProtocolTest {
    private fun device(
        rights: Int? = MeshRights.REMOTE_CONTROL,
        conn: Int = 1,
        known: Boolean = true,
        agentId: Int? = 1,
        os: String = "Windows",
    ) = MeshDevice(
        nodeId = "node//example",
        meshId = "mesh//example",
        name = "PC",
        osDescription = os,
        conn = conn,
        connectivityKnown = known,
        agentId = agentId,
        rights = rights,
    )

    private fun reply(value: Any): JSONObject = JSONObject().put("value", value)

    @Test
    fun processesParseStringAndObjectWireShapes() {
        val fromString = reply(
            """{"10":{"cmd":"b.exe","user":"bob","path":"C:\\Tools\\b.exe"},"2":{"cmd":"a.exe"}}""",
        )
        val rows = TaskToolsProtocol.processes(fromString)
        assertEquals(listOf(2L, 10L), rows.map { it.pid })
        assertEquals("a.exe", rows[0].name)
        assertNull(rows[0].user)
        assertNull(rows[0].command)
        assertEquals("bob", rows[1].user)
        assertEquals("C:\\Tools\\b.exe", rows[1].command)

        val fromObject = reply(JSONObject().put("7", JSONObject().put("cmd", "x").put("user", "u")))
        assertEquals(AgentProcess(7L, "x", "u", null), TaskToolsProtocol.processes(fromObject).single())
    }

    @Test
    fun processesAcceptEmptyObjectButRejectAnyMalformedEntry() {
        assertTrue(TaskToolsProtocol.processes(reply(JSONObject())).isEmpty())
        assertTrue(TaskToolsProtocol.processes(reply("{}")).isEmpty())

        val malformed = listOf(
            JSONObject(),
            reply(JSONArray()),
            reply("[]"),
            reply("not json"),
            reply("{} trailing"),
            reply("""{"-1":{"cmd":"x"}}"""),
            reply("""{"1.5":{"cmd":"x"}}"""),
            reply("""{"x":{"cmd":"x"}}"""),
            reply("""{"1":"x"}"""),
            reply("""{"1":{}}"""),
            reply("""{"1":{"cmd":1}}"""),
            reply("""{"1":{"cmd":"x","user":2}}"""),
            reply("""{"1":{"cmd":"x","path":[]}}"""),
            reply(JSONObject().put("1", JSONObject.NULL)),
        )
        malformed.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.processes(value) }
        }
    }

    @Test
    fun servicesParseWindowsArrayAndLinuxObject() {
        val windows = reply(
            """[
                {"name":"Spooler","displayName":"Print Spooler","status":{"state":"RUNNING","pid":1234},"startType":"Automatic"},
                {"name":"Off","displayName":"Off","status":{"state":"STOPPED"},"startType":"Manual"}
            ]""",
        )
        val rows = TaskToolsProtocol.services(windows)
        assertEquals(2, rows.size)
        assertEquals("Spooler", rows[0].name)
        assertEquals("Print Spooler", rows[0].displayName)
        assertEquals("RUNNING", rows[0].state)
        assertEquals("Automatic", rows[0].startType)
        assertEquals(1234L, rows[0].pid)
        assertEquals(true, rows[0].running)
        assertEquals(false, rows[1].running)

        val linux = reply(
            JSONObject()
                .put("1", JSONObject().put("name", "sshd").put("serviceType", "systemd"))
                .put("0", JSONObject().put("name", "cron").put("serviceType", "systemd")),
        )
        val linuxRows = TaskToolsProtocol.services(linux)
        assertEquals(listOf("cron", "sshd"), linuxRows.map { it.name })
        assertEquals("cron", linuxRows[0].displayName)
        assertNull(linuxRows[0].state)
        assertNull(linuxRows[0].running)
        assertEquals("systemd", linuxRows[0].serviceType)

        val stringObject = reply("""{"0":{"name":"cron","serviceType":"systemd"}}""")
        assertEquals("cron", TaskToolsProtocol.services(stringObject).single().name)
    }

    @Test
    fun servicesAcceptEmptyListsButRejectMalformedEntries() {
        assertTrue(TaskToolsProtocol.services(reply("[ ]")).isEmpty())
        assertTrue(TaskToolsProtocol.services(reply(JSONObject())).isEmpty())
        assertTrue(TaskToolsProtocol.services(reply("{}")).isEmpty())

        val malformed = listOf(
            JSONObject(),
            reply(1),
            reply("not json"),
            reply("[null]"),
            reply("""[{"displayName":"x"}]"""),
            reply("""[{"name":""}]"""),
            reply("""[{"name":"x","displayName":2}]"""),
            reply("""[{"name":"x","status":"RUNNING"}]"""),
            reply("""[{"name":"x","status":[]}]"""),
            reply("""[{"name":"x","status":{"state":1}}]"""),
            reply("""[{"name":"x","status":{"pid":-1}}]"""),
            reply("""[{"name":"x","status":{"pid":1.5}}]"""),
            reply("""[{"name":"x","status":{"pid":"1"}}]"""),
            reply(JSONObject().put("svc", JSONObject().put("name", "x"))),
            reply("""{"0":{"name":"x"}} trailing"""),
        )
        malformed.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.services(value) }
        }
    }

    @Test
    fun detailsChecksPidAndRetainsOnlyKnownScalarFields() {
        val source = JSONObject()
            .put("processName", "agent.exe")
            .put("processUser", "bob")
            .put("cmd", "agent.exe --run")
            .put("startTime", "2026-01-02T03:04:05.000Z")
            .put("privateMemorySize", 4096)
            .put("PriorityBoostEnabled", true)
            .put("path", "C:\\agent.exe")
            .put("secret", "must not leak")
            .put("nested", JSONObject().put("a", "b"))
            .put("nullField", JSONObject.NULL)
        val details = TaskToolsProtocol.details(JSONObject().put("pid", 42).put("value", source), 42)
        assertEquals("agent.exe", details["processName"])
        assertEquals("bob", details["processUser"])
        assertEquals("agent.exe --run", details["cmd"])
        assertEquals("2026-01-02T03:04:05.000Z", details["startTime"])
        assertEquals("4096", details["privateMemorySize"])
        assertEquals("true", details["PriorityBoostEnabled"])
        assertEquals("C:\\agent.exe", details["path"])
        assertFalse(details.containsKey("secret"))
        assertFalse(details.containsKey("nested"))
        assertFalse(details.containsKey("nullField"))
        assertEquals(7, details.size)

        val stringValue = JSONObject().put("pid", 42).put("value", """{"processName":"x"}""")
        assertEquals("x", TaskToolsProtocol.details(stringValue, 42)["processName"])
    }

    @Test
    fun detailsRejectWrongPidMissingValueAndNonObjectValue() {
        val source = JSONObject().put("processName", "x")
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("pid", 43).put("value", source), 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("value", source), 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("pid", "42").put("value", source), 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("pid", 42).put("value", JSONArray()), 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("pid", 42).put("value", "not json"), 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.details(JSONObject().put("pid", 42).put("value", source), -1)
        }
    }

    @Test
    fun visibilityCoversKnownDesktopAgentsOnly() {
        assertTrue(TaskToolsProtocol.visible(device(agentId = 1, os = "Windows 11")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 5, os = "Linux")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 11, os = "")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 16, os = "")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 29, os = "")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 42, os = "Darwin")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 42, os = "Mac OS X")))
        assertTrue(TaskToolsProtocol.visible(device(agentId = 42, os = "Apple macOS 14")))
        assertFalse(TaskToolsProtocol.visible(device(agentId = 14, os = "Android")))
        assertFalse(TaskToolsProtocol.visible(device(agentId = 42, os = "FreeBSD")))
        assertFalse(TaskToolsProtocol.visible(device(agentId = 42, os = "GNU Mach")))
        assertFalse(TaskToolsProtocol.visible(device(agentId = 42, os = "Mach")))
        assertFalse(TaskToolsProtocol.visible(device(agentId = null, os = "Windows")))
    }

    @Test
    fun readRightsAllowFullAndEitherViewOrControl() {
        val base = device(rights = MeshRights.REMOTE_CONTROL)
        assertTrue(TaskToolsProtocol.canRead(base, true))
        assertTrue(TaskToolsProtocol.canMutate(base, true))
        assertTrue(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.REMOTE_VIEW), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = MeshRights.REMOTE_VIEW), true))
        assertTrue(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW), true))
        assertTrue(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.FULL), true))
        assertTrue(TaskToolsProtocol.canMutate(base.copy(rights = MeshRights.FULL), true))
        assertTrue(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.DESKTOP_LIMITED_INPUT), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.DESKTOP_LIMITED_INPUT), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = 8 or 65536), true))
        assertFalse(TaskToolsProtocol.canMutate(base, true, 0x2000))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = MeshRights.FULL), true, 0x2000))
    }

    @Test
    fun unknownOfflineAndStaleRightsFailClosed() {
        val base = device(rights = MeshRights.REMOTE_CONTROL)
        assertFalse(TaskToolsProtocol.canRead(base.copy(rights = null), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(rights = null), true))
        assertFalse(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.NONE), true))
        assertFalse(TaskToolsProtocol.canRead(base.copy(rights = MeshRights.MANAGE_COMPUTERS), true))
        assertFalse(TaskToolsProtocol.canRead(base.copy(rights = -2), true))
        assertFalse(TaskToolsProtocol.canRead(base.copy(conn = 0), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(conn = 0), true))
        assertFalse(TaskToolsProtocol.canRead(base.copy(connectivityKnown = false), true))
        assertFalse(TaskToolsProtocol.canMutate(base.copy(connectivityKnown = false), true))
        assertFalse(TaskToolsProtocol.canRead(base, false))
        assertFalse(TaskToolsProtocol.canMutate(base, false))
    }

    @Test
    fun readBuildsOnlyListRequests() {
        val ps = TaskToolsProtocol.read("node//example", "ps")
        assertEquals(3, ps.length())
        assertEquals("msg", ps.getString("action"))
        assertEquals("node//example", ps.getString("nodeid"))
        assertEquals("ps", ps.getString("type"))

        val services = TaskToolsProtocol.read("node//example", "services")
        assertEquals("services", services.getString("type"))
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.read("", "ps") }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.read("node\n//example", "ps") }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.read("node//example", "psinfo") }
    }

    @Test
    fun killRequiresConfirmationAndMutationRights() {
        val target = AgentProcess(42L, "explorer.exe", "bob", "C:\\Windows\\explorer.exe")
        val command = TaskToolsProtocol.kill(device(rights = MeshRights.REMOTE_CONTROL), true, target, true)
        assertEquals(4, command.length())
        assertEquals("msg", command.getString("action"))
        assertEquals("pskill", command.getString("type"))
        assertEquals("node//example", command.getString("nodeid"))
        assertEquals("42|explorer.exe", command.getString("value"))

        assertEquals(command.getString("value"), TaskToolsProtocol.kill(device(rights = MeshRights.FULL), true, target, true).getString("value"))
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.kill(device(), true, target, false) }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(rights = MeshRights.REMOTE_VIEW), true, target, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(rights = MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW), true, target, true)
        }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.kill(device(rights = null), true, target, true) }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.kill(device(conn = 0), true, target, true) }
        assertThrows(IllegalArgumentException::class.java) { TaskToolsProtocol.kill(device(), false, target, true) }
    }

    @Test
    fun killRejectsUnsafeAuditNamesAndNegativePid() {
        val target = AgentProcess(42L, "explorer.exe")
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(), true, target.copy(pid = -1L), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(), true, target.copy(name = "a|b"), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(), true, target.copy(name = "a\nb"), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(), true, target.copy(name = "  "), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.kill(device(), true, target.copy(name = "a\u0000b"), true)
        }
    }

    @Test
    fun serviceCommandsUseServiceNameAndAuditedWireTypes() {
        val target = AgentService("Spooler", "Print Spooler", state = "RUNNING")
        val actions = listOf(
            TaskServiceAction.Start to "serviceStart",
            TaskServiceAction.Stop to "serviceStop",
            TaskServiceAction.Restart to "serviceRestart",
        )
        for ((action, wire) in actions) {
            val command = TaskToolsProtocol.service(device(), true, target, action, true)
            assertEquals(4, command.length())
            assertEquals("msg", command.getString("action"))
            assertEquals(wire, command.getString("type"))
            assertEquals("node//example", command.getString("nodeid"))
            assertEquals("Spooler", command.getString("serviceName"))
            assertFalse(command.has("value"))
        }
        assertEquals(
            " Spooler ",
            TaskToolsProtocol.service(device(), true, target.copy(name = " Spooler "), TaskServiceAction.Start, true)
                .getString("serviceName"),
        )
    }

    @Test
    fun serviceMutationsRequireConfirmationPermissionAndExactName() {
        val target = AgentService("Spooler", "Print Spooler")
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(), true, target, TaskServiceAction.Start, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(rights = MeshRights.REMOTE_VIEW), true, target, TaskServiceAction.Start, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(rights = MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW), true, target, TaskServiceAction.Stop, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(rights = null), true, target, TaskServiceAction.Stop, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(conn = 0), true, target, TaskServiceAction.Restart, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(), false, target, TaskServiceAction.Restart, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(), true, target.copy(name = ""), TaskServiceAction.Start, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(), true, target.copy(name = "   "), TaskServiceAction.Start, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskToolsProtocol.service(device(), true, target.copy(name = "a\nb"), TaskServiceAction.Start, true)
        }
    }

    @Test
    fun criticalProcessesCoverPidNamesPathsAndMeshAgent() {
        assertTrue(TaskToolsProtocol.critical(AgentProcess(0L, "idle")))
        assertTrue(TaskToolsProtocol.critical(AgentProcess(1L, "anything")))
        assertTrue(TaskToolsProtocol.critical(AgentProcess(4L, "anything")))

        val criticalNames = listOf(
            "System",
            "smss",
            "smss.exe",
            "csrss.exe",
            "wininit.exe",
            "winlogon.exe",
            "services.exe",
            "lsass.exe",
            "init",
            "systemd",
            "MeshAgent",
            "Mesh Agent",
            "mesh agent.exe",
            "MeshCentral",
            "MeshCentral.exe",
            "C:\\Windows\\System32\\lsass.exe",
            "C:\\Program Files\\Mesh Agent\\MeshAgent.exe",
            "MESHAGENT.EXE",
        )
        criticalNames.forEach { name ->
            assertTrue(name, TaskToolsProtocol.critical(AgentProcess(100L, name)))
        }

        listOf("explorer.exe", "notepad.exe", "svchost.exe", "systemd-helper", "meshagentx", "").forEach { name ->
            assertFalse(name, TaskToolsProtocol.critical(AgentProcess(100L, name)))
        }
    }

    @Test
    fun criticalServicesCoverSystemAndMeshAgentAliases() {
        listOf("RPCSS", "DcomLaunch", "RpcEptMapper", "SamSs", "LSM", "Wininit", "EventLog", "Power", "PlugPlay", "Winmgmt")
            .forEach { name ->
                assertTrue(name, TaskToolsProtocol.critical(AgentService(name, "Other")))
            }
        listOf("MeshAgent", "Mesh Agent", "mesh agent", "MeshCentral", "MeshCentral Agent", "Mesh-Agent", "mesh_agent", "Mesh Agent Service")
            .forEach { name ->
                assertTrue(name, TaskToolsProtocol.critical(AgentService(name, "Other")))
            }
        assertTrue(TaskToolsProtocol.critical(AgentService("Other", "Mesh Agent")))
        assertFalse(TaskToolsProtocol.critical(AgentService("Spooler", "Print Spooler")))
        assertFalse(TaskToolsProtocol.critical(AgentService("Power Management", "Power Management")))
        assertFalse(TaskToolsProtocol.critical(AgentService("MeshAgentHelper", "MeshAgentHelper")))
    }

    @Test
    fun unknownServiceStateIsNotReportedAsRunningOrStopped() {
        assertEquals(true, AgentService("a", "a", state = "RUNNING").running)
        assertEquals(true, AgentService("a", "a", state = "running").running)
        assertEquals(false, AgentService("a", "a", state = "STOPPED").running)
        assertNull(AgentService("a", "a", state = null).running)
        assertNull(AgentService("a", "a", state = "PAUSED").running)
        assertNull(AgentService("a", "a", state = "").running)
    }
}
