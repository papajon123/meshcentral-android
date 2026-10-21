package ru.papajon.meshcentralmobile.mesh

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outgoing control messages and relay URLs.
 *
 * Shapes verified against MeshCentral 1.2.5: the tunnel request is validated in `meshuser.js`
 * (`command.type == 'tunnel'` requires the `nodeid` inside `value` to equal `command.nodeid`),
 * clipboard commands are gated by the domain flags `clipboardget`/`clipboardset`, and the relay
 * viewer URL follows `meshrelay.js` (`browser=1`, `p`, `nodeid`, `id`, `auth`).
 */
class MeshControlMessagesTest {
    @Test fun viewerUrlPreservesMeshCentralDomainPath() {
        val url = MeshControlMessages.relayViewerUrl("https://example.com/team/".toHttpUrl(), "node/test", "fixture")
        assertTrue(url.startsWith("wss://example.com/team/meshrelay.ashx?"))
    }

    private val nodeId = "node//hash1"

    @Test
    fun tunnelValueCarriesNodeIdAndRandomId() {
        assertEquals(
            "*/meshrelay.ashx?p=2&nodeid=node%2F%2Fhash1&id=abc123",
            MeshControlMessages.tunnelValue(nodeId, "abc123"),
        )
    }

    @Test
    fun tunnelValueCarriesRelayCookieWhenAvailable() {
        assertEquals(
            "*/meshrelay.ashx?p=2&nodeid=node%2F%2Fhash1&id=abc123&rauth=RELAY",
            MeshControlMessages.tunnelValue(nodeId, "abc123", relayCookie = "RELAY"),
        )
    }

    @Test
    fun tunnelRequestUsesRelayUsageTwoAndMatchingNodeId() {
        val json = MeshControlMessages.tunnelRequest(nodeId, "abc123", relayCookie = "RELAY", responseId = "android-tunnel")

        assertEquals("msg", json.getString("action"))
        assertEquals("tunnel", json.getString("type"))
        assertEquals(nodeId, json.getString("nodeid"))
        assertEquals(2, json.getInt("usage"))
        assertEquals("android-tunnel", json.getString("responseid"))
        val value = json.getString("value")
        assertTrue(value.startsWith("*/meshrelay.ashx?p=2&"))
        assertEquals(nodeId, queryValue(value, "nodeid"))
        assertEquals("abc123", queryValue(value, "id"))
        assertEquals("RELAY", queryValue(value, "rauth"))
    }

    @Test
    fun viewerUrlTargetsTheRelayWithBrowserFlagAndAuthCookie() {
        val base = "https://meshcentral.example.com/".toHttpUrl()
        val url = MeshControlMessages.relayViewerUrl(base, nodeId, "abc123", authCookie = "COOKIE", relayCookie = "RELAY")

        assertTrue(url, url.startsWith("wss://meshcentral.example.com/meshrelay.ashx?"))
        val parsed = url.replaceFirst("wss://", "https://").toHttpUrl()
        assertEquals("1", parsed.queryParameter("browser"))
        assertEquals("2", parsed.queryParameter("p"))
        assertEquals(nodeId, parsed.queryParameter("nodeid"))
        assertEquals("abc123", parsed.queryParameter("id"))
        assertEquals("COOKIE", parsed.queryParameter("auth"))
        assertEquals("RELAY", parsed.queryParameter("rauth"))
    }

    @Test
    fun viewerUrlWorksOverPlainWsInDebugBuilds() {
        val base = "http://10.0.2.2:8080/".toHttpUrl()
        val url = MeshControlMessages.relayViewerUrl(base, nodeId, "abc123")

        assertTrue(url, url.startsWith("ws://10.0.2.2:8080/meshrelay.ashx?"))
    }

    @Test
    fun clipboardRequestsUseMessageTypeAndNodeId() {
        val fetch = MeshControlMessages.fetchClipboard(nodeId, tag = "t1")
        assertEquals("msg", fetch.getString("action"))
        assertEquals("getclip", fetch.getString("type"))
        assertEquals(nodeId, fetch.getString("nodeid"))
        assertEquals("t1", fetch.getString("tag"))

        val send = MeshControlMessages.sendClipboard(nodeId, "hello world", tag = "t2")
        assertEquals("setclip", send.getString("type"))
        assertEquals("hello world", send.getString("data"))
        assertEquals("t2", send.getString("tag"))
    }

    @Test
    fun tunnelIdIsRandomHex() {
        val first = MeshControlMessages.randomTunnelId(java.security.SecureRandom())
        val second = MeshControlMessages.randomTunnelId(java.security.SecureRandom())

        assertEquals(32, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
        assertTrue(first != second)
    }

    private fun queryValue(relativeUrl: String, name: String): String? {
        val url = relativeUrl.substringAfter('?')
        return url.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
    }
}
