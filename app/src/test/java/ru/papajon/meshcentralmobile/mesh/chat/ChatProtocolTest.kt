package ru.papajon.meshcentralmobile.mesh.chat

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

class ChatProtocolTest {
    private val device = MeshDevice("node//lab", "mesh//lab", "Lab", conn = 1, agentId = 6, rights = MeshRights.CHAT_NOTIFY or MeshRights.REMOTE_CONTROL)

    @Test fun textMatchesMessengerWireFormatWithoutHtmlEncoding() {
        val text = "<b>Hello</b> & \"Привет\"\nمرحبا 🌍"
        val json = JSONObject(ChatProtocol.message(text))
        assertEquals(setOf("action", "msg"), json.keySet())
        assertEquals("chat", json.getString("action"))
        assertEquals(text, json.getString("msg"))
        assertEquals(ChatFrame.Text(text), ChatProtocol.parse(json.toString()))
    }
    @Test fun exactReadyAndControlFrames() {
        assertEquals(ChatFrame.Ready(false), ChatProtocol.parse("c"))
        assertEquals(ChatFrame.Ready(true), ChatProtocol.parse("cr"))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse(" c"))
        assertEquals(ChatFrame.Ping, ChatProtocol.parse("""{"ctrlChannel":"102938","type":"ping"}"""))
        assertEquals(ChatFrame.Ping, ChatProtocol.parse("""{"type":"ping","ctrlChannel":"102938"}"""))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse("""{"ctrlChannel":"102938","type":"pong"}"""))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse(ChatProtocol.pong()))
        assertEquals(ChatFrame.PeerProof, ChatProtocol.parse("""{"action":"random","random":0.5}"""))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse("""{"action":"random"}"""))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse("""{"action":"random","random":2}"""))
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse("""{"action":"random","random":"0.5"}"""))
        assertEquals("102938", JSONObject(ChatProtocol.pong()).getString("ctrlChannel"))
        assertEquals(ChatFrame.Refused, ChatProtocol.parse("""{"action":"close","cause":"noauth"}"""))
    }
    @Test fun malformedUnknownAndNonTextMessagesAreIgnored() {
        listOf("{", "[]", "null", "", "[1]", """{"action":"chat","msg":1}""", """{"action":"chat","msg":null}""",
            """{"action":"file","name":"test"}""", """{"action":"webRtcSdp","sdp":{}}""",
            """{"action":"outtext","value":true}""", """{"action":"chat","msg":""}""",
            """{"action":"chat","msg":"a\q"}""", """{"action":"chat","msg":"ok"} trailing""",
            """{"action":"chat","msg":"ok","extra":}""")
            .forEach { assertEquals(ChatFrame.Ignore, ChatProtocol.parse(it)) }
    }
    @Test fun longChatIsTruncatedInsteadOfDropped() {
        val fiveThousand = "x".repeat(5_000)
        val frame = JSONObject().put("action", "chat").put("msg", fiveThousand).toString()
        assertEquals(ChatFrame.Text("x".repeat(ChatProtocol.MAX_TEXT), true), ChatProtocol.parse(frame))

        // Escapes make the wire frame larger than the old 4096*6+256 limit while the decoded text
        // is only slightly longer than MAX_TEXT; the frame must still be accepted.
        val escaped = "{\"action\":\"chat\",\"msg\":\"" + "\\u0041".repeat(4_300) + "\"}"
        assertTrue(escaped.length > ChatProtocol.MAX_TEXT * 6 + 256)
        assertEquals(ChatFrame.Text("A".repeat(ChatProtocol.MAX_TEXT), true), ChatProtocol.parse(escaped))
    }
    @Test fun chatFieldsArriveInAnyOrderAndForeignValuesAreSkipped() {
        assertEquals(ChatFrame.Text("hello"), ChatProtocol.parse("""{"msg":"hello","action":"chat"}"""))
        assertEquals(
            ChatFrame.Text("hello"),
            ChatProtocol.parse("""{"msg":"hello","extra":{"nested":[1,{"x":"}"}]},"action":"chat"}"""),
        )
        assertEquals(ChatFrame.FileOffer(0.25), ChatProtocol.parse("""{"id":0.25,"action":"file"}"""))
        val deep = "{\"action\":\"chat\",\"msg\":\"ok\",\"extra\":" + "[".repeat(80) + "]".repeat(80) + "}"
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse(deep))
    }
    @Test fun truncationDoesNotSplitSurrogatePair() {
        val globe = "\uD83C\uDF0D"
        val prefix = "{\"action\":\"chat\",\"msg\":\""
        val suffix = "\"}"
        assertEquals(
            ChatFrame.Text("a".repeat(ChatProtocol.MAX_TEXT - 1), true),
            ChatProtocol.parse(prefix + "a".repeat(ChatProtocol.MAX_TEXT - 1) + globe + suffix),
        )
        assertEquals(
            ChatFrame.Text("a".repeat(ChatProtocol.MAX_TEXT - 2) + globe),
            ChatProtocol.parse(prefix + "a".repeat(ChatProtocol.MAX_TEXT - 2) + globe + suffix),
        )
        assertEquals(
            ChatFrame.Text("a".repeat(ChatProtocol.MAX_TEXT - 1), true),
            ChatProtocol.parse(prefix + "a".repeat(ChatProtocol.MAX_TEXT - 1) + "\\uD83C\\uDF0D" + suffix),
        )
    }
    @Test fun fileOffersRequireNumericIdInUnitInterval() {
        assertEquals(ChatFrame.FileOffer(0.0), ChatProtocol.parse("""{"action":"file","id":0}"""))
        assertEquals(ChatFrame.FileOffer(0.999), ChatProtocol.parse("""{"action":"fileUploadStart","id":0.999}"""))
        assertEquals(ChatFrame.FileOffer(0.5), ChatProtocol.parse("""{"id":5e-1,"action":"file"}"""))
        listOf(
            """{"action":"file","id":1}""",
            """{"action":"file","id":-0.1}""",
            """{"action":"file","id":1.0}""",
            """{"action":"file","id":"0.5"}""",
            """{"action":"file","id":null}""",
            """{"action":"file","id":true}""",
            """{"action":"file"}""",
            """{"action":"file","id":1e999}""",
            """{"action":"fileUploadStart","id":0.5,"id":"x"}""",
        ).forEach { assertEquals(ChatFrame.Ignore, ChatProtocol.parse(it)) }
    }
    @Test fun fileCancelUsesNumericId() {
        val json = JSONObject(ChatProtocol.cancelFile(0.999))
        assertEquals(setOf("action", "id"), json.keySet())
        assertEquals("fileUploadCancel", json.getString("action"))
        assertTrue(json.get("id") is Number)
        assertEquals(0.999, json.getDouble("id"), 0.0)
        assertEquals(0.0, JSONObject(ChatProtocol.cancelFile(0.0)).getDouble("id"), 0.0)
    }
    @Test fun textAndFrameLimitsAreBounded() {
        val boundary = "x".repeat(ChatProtocol.MAX_TEXT)
        assertEquals(ChatFrame.Text(boundary), ChatProtocol.parse(ChatProtocol.message(boundary)))
        assertThrows(IllegalArgumentException::class.java) { ChatProtocol.message(boundary + "x") }
        assertThrows(IllegalArgumentException::class.java) { ChatProtocol.message(" \n ") }
        assertEquals(
            ChatFrame.Text(boundary, true),
            ChatProtocol.parse(JSONObject().put("action", "chat").put("msg", boundary + "x").toString()),
        )
        assertEquals(ChatFrame.Ignore, ChatProtocol.parse("{" + "x".repeat(30_000)))
    }
    @Test fun relayUsesCookieNotAgentRauthAndPreservesDomainPath() {
        val request = ChatProtocol.request("https://server.example/team/?old=1#part".toHttpUrl(), "node/team/a@b", "user/team/admin", "synthetic+/cookie=")
        assertEquals("/team/meshrelay.ashx", request.url.encodedPath)
        assertEquals("meshmessenger/node/team/a@b/user/team/admin", request.url.queryParameter("id"))
        assertEquals("synthetic+/cookie=", request.url.queryParameter("auth"))
        assertEquals(setOf("id", "auth"), request.url.queryParameterNames)
        assertNull(request.url.fragment)
        assertNull(request.header("x-meshauth"))
        assertNull(request.header("Cookie"))
        val invite = ChatProtocol.invite(device.nodeId)
        assertEquals(setOf("action", "nodeid"), invite.keySet())
        assertEquals("meshmessenger", invite.getString("action"))
    }
    @Test fun onlyCurrentOnlineComputerWithExplicitChatGrantIsAllowed() {
        assertTrue(ChatProtocol.allowed(device, true))
        assertTrue(ChatProtocol.allowed(device.copy(rights = MeshRights.FULL), true))
        listOf(device.copy(conn = 0), device.copy(conn = 2), device.copy(agentId = null), device.copy(agentId = 14),
            device.copy(rights = null), device.copy(rights = MeshRights.REMOTE_CONTROL), device.copy(rights = MeshRights.CHAT_NOTIFY), device.copy(rights = -2),
            device.copy(connectivityKnown = false)).forEach { assertFalse(ChatProtocol.allowed(it, true)) }
        assertFalse(ChatProtocol.allowed(device, false))
        assertTrue(ChatProtocol.authorized(device.copy(conn = 0)))
    }
    @Test fun authorizedNeedsChatAndRemoteRightsButNotConnectivity() {
        assertTrue(ChatProtocol.authorized(device))
        assertTrue(ChatProtocol.authorized(device.copy(rights = MeshRights.FULL)))
        assertTrue(ChatProtocol.authorized(device.copy(rights = MeshRights.CHAT_NOTIFY or MeshRights.REMOTE_VIEW)))
        assertTrue(ChatProtocol.authorized(device.copy(conn = 0, connectivityKnown = false)))
        listOf(device.copy(agentId = null), device.copy(agentId = 14), device.copy(rights = null),
            device.copy(rights = MeshRights.CHAT_NOTIFY), device.copy(rights = MeshRights.REMOTE_CONTROL),
            device.copy(rights = MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW), device.copy(rights = -2))
            .forEach { assertFalse(ChatProtocol.authorized(it)) }
    }
}
