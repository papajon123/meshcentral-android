package ru.papajon.meshcentralmobile.mesh

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.chat.ChatController
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * Synthetic MeshCentral pair behind a real HTTPS MockWebServer.
 *
 * The app is the administrator side of `/meshrelay.ashx?id=meshmessenger/...`; the second peer is
 * the remote browser MeshCentral opens after `meshmessenger`. The server never sends `c` until
 * both peers are registered, forwards text frames both ways, and closes the surviving peer when
 * either relay goes away. An unpaired relay is closed after the real 15 s MeshCentral timeout.
 *
 * Every credential below is synthetic test data; no production or local development endpoint is
 * contacted.
 */
class ChatRelayFixture(initialAutoOpen: Boolean = true) : AutoCloseable {
    companion object {
        const val NODE_ID = "node/test/device"
        const val MESH_ID = "mesh/test/group"
        const val USER_ID = "user/test/fixture"
        const val ADMIN_COOKIE = "synthetic-user-cookie"
        const val RELAY_COOKIE = "synthetic-agent-cookie"
        const val UNPAIRED_TIMEOUT_MILLIS = 15_000L
        const val BROWSER_RECONNECT_MILLIS = 500L
    }

    private val lock = Any()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val vault = SecureVault(context, "chat-relay-test-${System.nanoTime()}")
    val client = MeshCentralClient(vault, scope)
    val certificate = HeldCertificate.Builder().commonName("chat.example").build()
    val web = MockWebServer()
    val adminRequests = CopyOnWriteArrayList<RecordedRequest>()
    val browserRequests = CopyOnWriteArrayList<RecordedRequest>()
    val chatFrames = CopyOnWriteArrayList<String>()
    val browserFrames = CopyOnWriteArrayList<String>()
    val controls = CopyOnWriteArrayList<WebSocket>()
    val inviteNodeIds = CopyOnWriteArrayList<String>()
    val invites = AtomicInteger()
    val browserConnections = AtomicInteger()
    val pairings = AtomicInteger()
    val adminCloses = AtomicInteger()
    val duplicates = AtomicInteger()

    @Volatile var autoOpen = initialAutoOpen
    @Volatile var reopenBrowserAfterLoss = false
    @Volatile var browserDelayMillis = 0L
    @Volatile var requiredAdminCookie = ADMIN_COOKIE
    @Volatile var issuedAdminCookie = ADMIN_COOKIE
    @Volatile private var nodeConn = 1
    @Volatile private var admin: WebSocket? = null
    @Volatile private var browser: WebSocket? = null
    @Volatile private var browserClientSocket: WebSocket? = null
    @Volatile private var control: WebSocket? = null
    private var paired = false
    private val retained = mutableSetOf<WebSocket>()
    private val pairedBrowsers = mutableSetOf<WebSocket>()
    private var timeout: Job? = null
    private var browserRetry: Job? = null
    private val browserClient: OkHttpClient

    val url: String get() = "https://127.0.0.1:${web.port}/test/"

    init {
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        web.useHttps(serverCertificates.sslSocketFactory(), false)
        val browserTrust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        browserClient = OkHttpClient.Builder()
            .sslSocketFactory(browserTrust.sslSocketFactory(), browserTrust.trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (target.encodedPath.endsWith("/control.ashx")) {
                    return MockResponse().withWebSocketUpgrade(controlListener())
                }
                if (!target.encodedPath.endsWith("/meshrelay.ashx")) {
                    return MockResponse().setResponseCode(404)
                }
                if (target.queryParameter("auth") == null && target.queryParameter("id") == "meshmessenger/$NODE_ID/$USER_ID") {
                    browserRequests += request
                    return MockResponse().withWebSocketUpgrade(serverBrowserListener())
                }
                val id = target.queryParameter("id").orEmpty()
                if (target.queryParameter("auth") != requiredAdminCookie ||
                    id != "meshmessenger/$NODE_ID/$USER_ID") {
                    return MockResponse().setResponseCode(401)
                }
                adminRequests += request
                return MockResponse().withWebSocketUpgrade(adminListener())
            }
        }
        web.start()
    }

    fun login() {
        onMain {
            client.configureServer(url)
            client.setPinnedServerKey(spkiSha256(certificate.certificate))
            client.login(url, "fixture-admin", "synthetic-password")
        }
        await { client.clientState.value.devicesCurrent }
    }

    fun chat(onBackground: () -> Unit = {}): ChatController {
        lateinit var chat: ChatController
        onMain {
            chat = client.createChat(client.clientState.value.devices.single(), onBackground)
            chat.start()
        }
        return chat
    }

    fun publishNodes(conn: Int) {
        synchronized(lock) { nodeConn = conn }
        control?.send(nodesFrame())
    }

    fun publishMeshes(rights: Int) {
        control?.send(meshesFrame(rights))
    }

    fun dropControl() {
        val socket = synchronized(lock) { control }
        socket?.close(1000, null)
    }
    fun publishCookie(value: String) {
        control?.send(JSONObject().put("action", "authcookie").put("cookie", value).put("rcookie", RELAY_COOKIE).toString())
    }

    fun closeBrowser() {
        val socket = synchronized(lock) { browserClientSocket ?: browser }
        socket?.close(1000, null)
    }

    fun browserSend(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var socket = synchronized(lock) { browserClientSocket }
        while (socket == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            socket = synchronized(lock) { browserClientSocket }
        }
        val open = socket ?: error("The browser peer is not open")
        check(open.send(text)) { "The browser peer refused the frame" }
    }

    /** Graceful transport loss of both relay sockets. For blackholes use retainAbruptAdminLoss. */
    fun dropRelays() {
        var adminSocket: WebSocket? = null
        var browserSocket: WebSocket? = null
        var browserClient: WebSocket? = null
        synchronized(lock) {
            adminSocket = admin
            browserSocket = browser
            browserClient = browserClientSocket
            admin = null
            browser = null
            browserClientSocket = null
            paired = false
            timeout?.cancel()
            timeout = null
        }
        // A graceful close is still a transport loss for the controller: while Connected it must
        // recover silently. This also guarantees an onClosing/onClosed callback on the app side.
        adminSocket?.close(1000, null)
        browserSocket?.close(1000, null)
        browserClient?.close(1000, null)
        if (reopenBrowserAfterLoss) scheduleBrowserReconnect()
    }

    private fun controlListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            controls += webSocket
            synchronized(lock) { control = webSocket }
            // MeshCentral pushes the identity and initial snapshots; the app also asks for them.
            webSocket.send(userInfoFrame())
            webSocket.send(meshesFrame(MeshRights.FULL))
            webSocket.send(nodesFrame())
            webSocket.send(authCookieFrame())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("action")) {
                "userinfo" -> webSocket.send(userInfoFrame())
                "serverinfo" -> webSocket.send("""{"action":"serverinfo","serverinfo":{"name":"Fixture","domain":"test"}}""")
                "meshes" -> webSocket.send(meshesFrame(MeshRights.FULL))
                "nodes" -> webSocket.send(nodesFrame())
                "authcookie" -> webSocket.send(authCookieFrame())
                "meshmessenger" -> {
                    invites.incrementAndGet()
                    inviteNodeIds += json.optString("nodeid")
                    if (autoOpen) openBrowser()
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { if (control === webSocket) control = null }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { if (control === webSocket) control = null }
        }
    }

    private fun adminListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (paired) { duplicates.incrementAndGet(); webSocket.close(1000, null); return }
                // Real meshrelay pairs by id, not by role: a second authenticated socket can
                // pair with the first abandoned administrator socket and receive c.
                if (admin != null) browser = webSocket else admin = webSocket
                scheduleUnpairedTimeout(webSocket)
                pairLocked()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            chatFrames += text
            val peer = synchronized(lock) {
                if (!paired || webSocket in retained) null
                else if (admin === webSocket) browser else if (browser === webSocket) admin else null
            }
            peer?.send(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            adminGone(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = adminGone(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = adminGone(webSocket)
    }

    private fun serverBrowserListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            browserConnections.incrementAndGet()
            synchronized(lock) {
                if (paired) { duplicates.incrementAndGet(); webSocket.close(1000, null); return }
                browser = webSocket
                scheduleUnpairedTimeout(webSocket)
                pairLocked()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val peer = synchronized(lock) {
                if (!paired || browser !== webSocket) null else admin
            }
            peer?.send(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            browserGone(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = browserGone(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = browserGone(webSocket)
    }

    /** Client half of the synthetic remote browser. The server half drives pairing. */
    private fun browserClientListener() = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "c" || text == "cr") webSocket.send("""{"action":"random","random":0.5}""")
            if (text.startsWith("{")) {
                browserFrames += text
                if (JSONObject(text).optString("type") == "ping") webSocket.send("""{"ctrlChannel":"102938","type":"pong"}""")
            }
        }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { browserClientSocket = webSocket }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { if (browserClientSocket === webSocket) browserClientSocket = null }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { if (browserClientSocket === webSocket) browserClientSocket = null }
        }
    }

    /** Called with [lock] held. A browser may arrive before or after the administrator relay. */
    private fun pairLocked() {
        val adminSocket = admin ?: return
        val browserSocket = browser ?: return
        if (paired) return
        paired = true
        pairedBrowsers.add(browserSocket)
        pairings.incrementAndGet()
        timeout?.cancel()
        timeout = null
        adminSocket.send("c")
        browserSocket.send("c")
    }

    /** Called with [lock] held. */
    private fun scheduleUnpairedTimeout(webSocket: WebSocket) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(UNPAIRED_TIMEOUT_MILLIS)
            val close = synchronized(lock) {
                if ((admin === webSocket || browser === webSocket) && !paired) {
                    if (admin === webSocket) admin = null else browser = null
                    webSocket
                } else {
                    null
                }
            }
            close?.close(1000, null)
        }
    }

    private fun adminGone(webSocket: WebSocket) {
        var peer: WebSocket? = null
        var closed = false
        synchronized(lock) {
            if (webSocket in retained) return
            if (browser === webSocket) { browserGone(webSocket); return }
            if (admin === webSocket) {
                admin = null
                paired = false
                closed = true
                timeout?.cancel()
                timeout = null
                peer = browser
            }
        }
        if (closed) adminCloses.incrementAndGet()
        peer?.close(1000, null)
    }

    /** Cancel the real app TCP sockets without a WebSocket close frame, while the simulated
     * server retains its slot. Ignoring the abandoned socket's server callback models the
     * interval before TCP keepalive detects a network blackhole. */
    fun retainAbruptAdminLoss() {
        synchronized(lock) { retained.add(admin ?: error("No administrator relay to interrupt")) }
        onMain { client.onNetworkLost() }
        onMain { client.onNetworkAvailable() }
    }

    /** Models server TCP keepalive finally expiring; the existing browser may reconnect. */
    fun expireRetainedPair() {
        val abandoned = synchronized(lock) { retained.toList().also { retained.clear() } }
        abandoned.forEach(::adminGone)
    }

    fun duplicateAdmin() = browserClient.newWebSocket(Request.Builder().url(web.url("/meshrelay.ashx")
        .newBuilder().addQueryParameter("id", "meshmessenger/$NODE_ID/$USER_ID")
        .addQueryParameter("auth", ADMIN_COOKIE).build()).build(), object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    })

    private fun browserGone(webSocket: WebSocket) {
        var peer: WebSocket? = null
        var reconnect = false
        synchronized(lock) {
            val wasPaired = pairedBrowsers.remove(webSocket)
            if (browser === webSocket) {
                browser = null
                paired = false
                peer = admin
                reconnect = wasPaired && reopenBrowserAfterLoss
            }
        }
        peer?.close(1000, null)
        if (reconnect) scheduleBrowserReconnect()
    }

    private fun scheduleBrowserReconnect() {
        browserRetry?.cancel()
        browserRetry = scope.launch {
            delay(BROWSER_RECONNECT_MILLIS)
            openBrowser()
        }
    }

    fun openExistingBrowser() = openBrowser()

    private fun openBrowser() {
        val wait = browserDelayMillis
        if (wait > 0) {
            scope.launch {
                delay(wait)
                openBrowserNow()
            }
        } else {
            openBrowserNow()
        }
    }

    private fun openBrowserNow() {
        if (synchronized(lock) { browser != null }) return
        val target = "https://127.0.0.1:${web.port}/meshrelay.ashx".toHttpUrl().newBuilder()
            .addQueryParameter("id", "meshmessenger/$NODE_ID/$USER_ID")
            .build()
        browserClient.newWebSocket(Request.Builder().url(target).build(), browserClientListener())
    }

    private fun userInfoFrame(): String = JSONObject()
        .put("action", "userinfo")
        .put(
            "userinfo",
            JSONObject().put("_id", USER_ID).put("name", "fixture-admin").put("siteadmin", 0),
        )
        .toString()

    private fun meshesFrame(rights: Int): String = JSONObject()
        .put("action", "meshes")
        .put(
            "meshes",
            org.json.JSONArray().put(
                JSONObject()
                    .put("_id", MESH_ID)
                    .put("name", "Fixture")
                    .put("links", JSONObject().put(USER_ID, JSONObject().put("rights", rights))),
            ),
        )
        .toString()

    private fun nodesFrame(): String = JSONObject()
        .put("action", "nodes")
        .put(
            "nodes",
            JSONObject().put(
                MESH_ID,
                org.json.JSONArray().put(
                    JSONObject()
                        .put("_id", NODE_ID)
                        .put("meshid", MESH_ID)
                        .put("name", "Fixture PC")
                        .put("conn", nodeConn)
                        .put("mtype", 2)
                        .put("agent", JSONObject().put("id", 3).put("caps", 7)),
                ),
            ),
        )
        .toString()

    private fun authCookieFrame(): String = JSONObject()
        .put("action", "authcookie")
        .put("cookie", issuedAdminCookie)
        .put("rcookie", RELAY_COOKIE)
        .toString()

    fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    fun await(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        org.junit.Assert.assertTrue("Timed out waiting for the chat fixture", condition())
    }

    override fun close() {
        timeout?.cancel()
        browserRetry?.cancel()
        onMain {
            client.close()
            scope.cancel()
            vault.clearCredentials()
        }
        val sockets = synchronized(lock) {
            val open = listOfNotNull(admin, browser, browserClientSocket, control)
            admin = null
            browser = null
            browserClientSocket = null
            control = null
            paired = false
            open
        }
        sockets.forEach { it.close(1000, null) }
        browserClient.dispatcher.executorService.shutdown()
        browserClient.connectionPool.evictAll()
        web.shutdown()
    }
}
