package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCommandsTest {
    @Test fun consoleActionsRequireBothControlAndConsoleRights() {
        val phone = MeshDevice("node//phone", "mesh//test", "Phone", conn = 1, agentId = 14)
        for (rights in listOf(0, 8, 16, 24, -1)) {
            for (action in PhoneAction.entries) {
                val console = action !in setOf(PhoneAction.Vibrate, PhoneAction.Flash)
                assertEquals("rights=$rights action=$action", rights and 8 != 0 && (!console || rights and 16 != 0),
                    PhoneCommands.canSend(phone.copy(rights = rights), true, action))
                assertFalse(PhoneCommands.canSend(phone.copy(rights = rights), false, action))
            }
        }
    }
    private val nodeId = "node//phone"
    private fun command(action: PhoneAction, text: String = "", title: String = "") =
        PhoneCommands.message(nodeId, PhoneRequest(action, text, title))
    private fun exact(expected: String, actual: JSONObject) = assertTrue(actual.toString(), JSONObject(expected).similar(actual))

    @Test fun locateCommandsUseAuditedActionTypesAndMilliseconds() {
        exact("""{"action":"poweraction","nodeids":["node//phone"],"actiontype":401,"time":5000}""", command(PhoneAction.Vibrate))
        exact("""{"action":"poweraction","nodeids":["node//phone"],"actiontype":400,"time":10000}""", command(PhoneAction.Flash))
    }

    @Test fun urlAndDialUseExactWireShapes() {
        exact("""{"action":"msg","type":"console","nodeid":"node//phone","value":"openbrowser \"https://example.com/path?q=1\""}""", command(PhoneAction.OpenUrl, "https://example.com/path?q=1"))
        exact("""{"action":"msg","type":"console","nodeid":"node//phone","value":"dial +123*45%23"}""", command(PhoneAction.Dial, "+123*45#"))
        assertEquals(listOf("openbrowser", "http://example.com"), parseLikeAgent(command(PhoneAction.OpenUrl, "http://example.com").getString("value")))
        assertEquals("dial *%2306%23", command(PhoneAction.Dial, "*#06#").getString("value"))
        assertEquals(listOf("openbrowser", "https://example.com/a%20b%22c"), parseLikeAgent(command(PhoneAction.OpenUrl, "https://example.com/a%20b%22c").getString("value")))
    }

    @Test fun alertAndToastRoundTripThroughTheActualAgentArgumentGrammar() {
        val text = "Привет \"телефон\"\nНовая строка 😀 \\ конец"
        val alert = command(PhoneAction.Alert, text, "Заголовок \"А\"")
        val value = JSONObject(alert.toString()).getString("value")
        assertEquals(listOf("alert", "Привет ”телефон”\nНовая строка 😀 \\ конец", "Заголовок ”А”"), parseLikeAgent(value))
        exact("""{"action":"msg","type":"console","nodeid":"node//phone","value":"toast \"Hello 😀\""}""", command(PhoneAction.Toast, "Hello 😀"))
        assertEquals(listOf("toast", "line 1\nline 2"), parseLikeAgent(command(PhoneAction.Toast, "line 1\nline 2").getString("value")))
    }

    @Test fun invalidInputsAreRejectedBeforeSending() {
        for (url in listOf("javascript:alert(1)", "file:///test", "https://", "https://example.com\n/path",
            "HTTPS://example.com", "Http://example.com", "https://example.com/a b", "https://example.com/a\"b")) {
            assertThrows(IllegalArgumentException::class.java) { command(PhoneAction.OpenUrl, url) }
        }
        for (number in listOf("123abc", "1 2", "", "1\n2", "１２３")) {
            assertThrows(IllegalArgumentException::class.java) { command(PhoneAction.Dial, number) }
        }
        assertThrows(IllegalArgumentException::class.java) { command(PhoneAction.Alert, "hello", "") }
        assertThrows(IllegalArgumentException::class.java) { command(PhoneAction.Toast, "a".repeat(1001)) }
        assertThrows(IllegalArgumentException::class.java) { command(PhoneAction.Toast, "\u0000") }
    }

    @Test fun knownCurrentAgentConnectionAndRight8AreRequired() {
        val phone = MeshDevice(nodeId, "mesh//test", "Phone", conn = 1, agentId = 14, rights = 8)
        assertTrue(PhoneCommands.canSend(phone, true))
        assertTrue(PhoneCommands.canSend(phone.copy(rights = -1), true))
        for (denied in listOf(phone.copy(rights = 256), phone.copy(rights = 16), phone.copy(rights = null),
            phone.copy(conn = 2), phone.copy(conn = 0), phone.copy(connectivityKnown = false), phone.copy(agentId = 6))) {
            assertFalse(PhoneCommands.canSend(denied, true))
        }
        assertFalse(PhoneCommands.canSend(phone, false))
    }

    // MeshAgent.kt:715-729: only ASCII space separates tokens, double quote toggles grouping.
    private fun parseLikeAgent(line: String): List<String> {
        val result = mutableListOf<String>(); var quoted = false; var token = ""
        for (c in line) {
            if (c == ' ' && !quoted) { if (token.isNotEmpty()) { result += token; token = "" } }
            else if (c == '"') quoted = !quoted else token += c
        }
        if (token.isNotEmpty()) result += token
        return result
    }
}
