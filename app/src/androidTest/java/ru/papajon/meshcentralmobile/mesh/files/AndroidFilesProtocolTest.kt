package ru.papajon.meshcentralmobile.mesh.files

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.ManagedRelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayCredentials
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability

class AndroidFilesProtocolTest {
    @Test fun officialAgentListsCategoriesAndOpensThemUsingBinaryUtf8Commands() = verifyCategories(withPhoneAdapter = false)

    @Test fun phoneAdapterPreservesRelativeCategoriesAndVirtualRoot() = verifyCategories(withPhoneAdapter = true)

    private fun verifyCategories(withPhoneAdapter: Boolean) {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()
        // Android 1.0.23 MeshTunnel: only binary onMessage processes JSON after text "5".
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("c") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "5") webSocket.send(byteArrayOf(0).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val request = JSONObject(bytes.utf8())
                if (request.optString("action") != "ls") return
                val entries = when (request.optString("path")) {
                    "" -> """[{"n":"Sdcard","t":2},{"n":"Images","t":2},{"n":"Audio","t":2},{"n":"Videos","t":2}]"""
                    "Images" -> """[{"n":"写真.jpg","t":3,"s":26}]"""
                    else -> return // Leading slash is not an Android virtual category.
                }
                request.put("dir", JSONArray(entries))
                webSocket.send(request.toString().toByteArray(Charsets.UTF_8).toByteString())
            }
        }))
        server.start()
        val relay = ManagedRelayConnection(RelayProtocol.AgentFiles, "node/test/android", http, scope,
            credentials = { RelayCredentials(server.url("/"), "fixture-cookie", null) },
            validSession = { true }, availability = { SessionAvailability.Available },
            sendControl = { true }, onFinished = {})
        val connection = if (withPhoneAdapter) AndroidFilesConnection(relay, scope) { error("No download expected") } else relay
        val controller = FilesController(connection, scope, InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.connect()
            await("Android categories must load") { !controller.state.value.busy && controller.state.value.entries.size == 4 }
            assertEquals(listOf("Audio", "Images", "Sdcard", "Videos"), controller.state.value.entries.map { it.name })
            controller.open(controller.state.value.entries.single { it.name == "Images" })
            await("Category must use its relative path") { controller.state.value.path == "Images" && !controller.state.value.busy }
            assertEquals("写真.jpg", controller.state.value.entries.single().name)
            controller.parent()
            await("Parent must return to the virtual root") { controller.state.value.path == "" && !controller.state.value.busy }
            assertEquals(4, controller.state.value.entries.size)
        } finally {
            controller.close()
            scope.cancel()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }
}
