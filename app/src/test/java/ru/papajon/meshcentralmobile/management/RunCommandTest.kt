package ru.papajon.meshcentralmobile.management

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*

class RunCommandTest {
    private val device = MeshDevice("node//example", "mesh//example", "Example", agentId = 4, conn = 1, rights = MeshRights.REMOTE_COMMAND)
    @Test fun exactMessageRequestsOutputFromAgentAccount() {
        assertTrue(JSONObject("""{"action":"runcommands","nodeids":["node//example"],"type":0,"cmds":"echo example","runAsUser":0,"reply":true}""").similar(RunCommand.create(device, true, "echo example", true)))
    }
    @Test fun commandRequiresPermissionFreshOnlineComputerAndConfirmation() {
        for (bad in listOf(device.copy(rights = null), device.copy(rights = 8), device.copy(conn = 0), device.copy(agentId = 14), device.copy(agentId = null))) {
            assertFalse(RunCommand.allowed(bad, true))
            assertThrows(IllegalArgumentException::class.java) { RunCommand.create(bad, true, "echo example", true) }
        }
        for (command in listOf("", "x".repeat(4097), "echo\u0000bad")) assertFalse(RunCommand.valid(command))
        assertThrows(IllegalArgumentException::class.java) { RunCommand.create(device, true, "echo example", false) }
        assertFalse(RunCommand.allowed(device, false))
    }
    @Test fun outputIsBoundedAndAcknowledgementIsNotOutput() {
        assertEquals("hello", RunCommand.output(JSONObject("""{"action":"msg","type":"runcommands","result":"hello","unknown":1}""")))
        assertEquals(65536, RunCommand.output(JSONObject().put("action", "msg").put("type", "runcommands").put("result", "x".repeat(70000))).length)
        assertThrows(IllegalArgumentException::class.java) { RunCommand.output(JSONObject("""{"action":"runcommands","result":"OK"}""")) }
    }

    @Test fun powershellUtf8IsExplicitWindowsOnlyAndPreservesCommandBody() {
        val command = "Write-Output 'Привет, мир'; Write-Error 'Ошибка'"
        val request = RunCommand.create(device, true, command, true, powershellUtf8 = true)
        assertEquals(2, request.getInt("type"))
        val script = request.getString("cmds")
        assertTrue(script.startsWith(RunCommand.POWERSHELL_UTF8))
        assertTrue(script.all { it.code < 128 })
        val encoded = Regex("FromBase64String\\('([^']+)'\\)").find(script)!!.groupValues[1]
        assertEquals(command, java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        assertEquals(command, RunCommand.create(device, true, command, true).getString("cmds"))
        assertEquals(0, RunCommand.create(device, true, command, true).getInt("type"))
        assertThrows(IllegalArgumentException::class.java) {
            RunCommand.create(device.copy(agentId = 6, osDescription = "Linux"), true, command, true, true)
        }
    }

    @Test fun utf8AgentFramePreservesCyrillicAndDoesNotGuessAtAlreadyDamagedOutput() {
        val bytes = byteArrayOf(0xd0.toByte(), 0x9f.toByte(), 0xd1.toByte(), 0x80.toByte(), 0xd0.toByte(), 0xb8.toByte(),
            0xd0.toByte(), 0xb2.toByte(), 0xd0.toByte(), 0xb5.toByte(), 0xd1.toByte(), 0x82.toByte())
        val text = bytes.toString(Charsets.UTF_8)
        val frame = JSONObject("""{"action":"msg","type":"runcommands","result":"$text\r\n","responseid":"fixture"}""")
        assertEquals("Привет\r\n", RunCommand.output(frame))
        // CP866 for Привет: agent conversion is upstream of the JSON frame. Do not falsely
        // repair arbitrary Unicode/Latin-1 content (which may be intentional command output).
        val cp866 = byteArrayOf(0x8f.toByte(), 0xe0.toByte(), 0xa8.toByte(), 0xa2.toByte(), 0xa5.toByte(), 0xe2.toByte())
        assertEquals("Привет", cp866.toString(java.nio.charset.Charset.forName("IBM866")))
        val damaged = cp866.toString(Charsets.UTF_8)
        assertNotEquals("Привет", damaged)
        assertEquals(damaged, RunCommand.output(JSONObject().put("action", "msg").put("type", "runcommands").put("result", damaged)))
        assertThrows(IllegalArgumentException::class.java) { RunCommand.output(JSONObject("""{"action":"msg","type":"runcommands","result":null}""")) }
    }
}
