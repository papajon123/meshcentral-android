package ru.papajon.meshcentralmobile.settings

import org.junit.Assert.*
import org.junit.Test

class ServerAddressInputTest {
    @Test fun pastedKeyMovesOutOfTheAddressWithoutChangingItsCase() {
        val parsed = ServerAddressInput.split("https://mesh.example/team/?key=AbC123")
        assertEquals("https://mesh.example/team/", parsed.address)
        assertEquals("AbC123", parsed.loginKey)
    }

    @Test fun extractingKeyDoesNotMakeOtherInvalidAddressPartsValid() {
        val parsed = ServerAddressInput.split("https://mesh.example/?key=fixture&other=value#section")
        assertEquals("fixture", parsed.loginKey)
        assertNotNull(ServerUrlValidator.validate(parsed.address, false))
        assertNull(ServerAddressInput.restorableDraft(parsed.address))
    }

    @Test fun ordinaryInputIsPreservedAndOnlySecretFreeEndpointsAreRestorable() {
        val input = "https://mesh.example/team"
        assertEquals(input, ServerAddressInput.split(input).address)
        assertNull(ServerAddressInput.split(input).loginKey)
        assertEquals(input, ServerAddressInput.restorableDraft(input))
        listOf("https://mesh.example/?key=fixture", "https://user:password@mesh.example/",
            "https://mesh.example/#fixture", "https://mesh.example/?key=a&key=b").forEach {
            assertNull(ServerAddressInput.restorableDraft(it))
        }
    }
}
