package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentConsentTest {
    @Test fun usesTheAuditedPromptBitForEachAgentProtocol() {
        assertEquals(8, AgentConsent.options(2, true)!!.getInt("consent"))
        assertEquals(16, AgentConsent.options(1, true)!!.getInt("consent"))
        assertEquals(32, AgentConsent.options(5, true)!!.getInt("consent"))
    }
    @Test fun preservesExistingOptionsAndDoesNotMutateTheCaller() {
        val original = JSONObject().put("consent", 64).put("cols", 80)
        val snapshot = AgentConsent.options(2, true, original)!!
        original.put("cols", 120)
        assertEquals(72, snapshot.getInt("consent"))
        assertEquals(80, snapshot.getInt("cols"))
        assertEquals(64, original.getInt("consent"))
        assertEquals(72, AgentConsent.options(2, false, snapshot)!!.getInt("consent"))
    }
    @Test fun disabledAndUnsupportedProtocolsDoNotInventAConsentRequest() {
        assertNull(AgentConsent.options(2, false))
        for (usage in listOf(10, 11, 13)) assertNull(AgentConsent.options(usage, true))
    }
}
