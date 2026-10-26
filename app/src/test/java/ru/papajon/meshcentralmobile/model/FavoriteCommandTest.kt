package ru.papajon.meshcentralmobile.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FavoriteCommandTest {
    @Test fun profileRoundTripAndLimit() {
        val commands = (1..25).map { FavoriteCommand("Command $it", "echo $it") }
        val profiles = listOf(ServerProfile("one", "One", "https://one.example", favoriteCommands = commands), ServerProfile("two", "Two", "https://two.example"))
        val result = ProfileCatalog.decode(ProfileCatalog.encode(profiles))
        assertEquals(20, result[0].favoriteCommands.size)
        assertTrue(result[1].favoriteCommands.isEmpty())
        assertEquals("echo 1", result[0].favoriteCommands.first().command)
    }
    @Test fun multilineAndControlCharactersCannotInjectEnterOrEscape() {
        for (text in listOf("echo x\n", "echo x\r", "x\u001b[A", "x\u0000", "x\u0085", "x\u2028", "x\u2029")) {
            assertFalse(FavoriteCommand("Example", text).valid)
        }
        val array = JSONArray().put(JSONObject().put("name", "Good").put("command", "echo example").put("unknown", "ignored"))
            .put(JSONObject().put("name", "Bad").put("command", "x\n"))
        assertEquals(listOf(FavoriteCommand("Good", "echo example")), FavoriteCommand.decode(array))
    }
}
