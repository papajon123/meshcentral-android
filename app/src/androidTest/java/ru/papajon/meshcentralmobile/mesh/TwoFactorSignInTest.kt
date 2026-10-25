package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * MeshCentral answers a password-only `x-meshauth` header with a `tokenrequired` close message and
 * `ws.close()`: an empty close frame that OkHttp reports as 1005. MockWebServer cannot send that
 * frame, so a small socket fixture reproduces the server's exact bytes.
 */
class TwoFactorSignInTest {
    @Test fun emptyCloseFrameKeepsTheCodeStepWithoutReconnecting() {
        assertChallengeStaysStable(AfterChallenge.EmptyCloseFrame)
    }

    @Test fun transportDropAfterTheChallengeDoesNotReconnect() {
        assertChallengeStaysStable(AfterChallenge.DropConnection)
    }

    @Test fun codeFromTheChallengeCompletesSignIn() {
        withClient(AfterChallenge.EmptyCloseFrame) { client, server ->
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            client.submitTwoFactorCode(VALID_CODE)
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            assertEquals(
                listOf(listOf("test-user", PASSWORD), listOf("test-user", PASSWORD, VALID_CODE)),
                server.authHeaders.toList(),
            )
        }
    }

    @Test fun rejectedPasswordIsNotRetried() {
        withClient(AfterChallenge.EmptyCloseFrame) { client, server ->
            client.login(server.url, "test-user", "wrong-password")
            await { client.clientState.value.lastError != null }
            Thread.sleep(RETRY_WINDOW_MILLIS)
            val state = client.clientState.value
            assertEquals(1, server.authHeaders.size)
            assertEquals(ConnectionState.Failed, state.connectionState)
            assertTrue(state.requiresSignIn)
            assertNull(state.twoFactorChallenge)
            assertEquals(UiText.Resource(R.string.runtime_auth_bad_credentials), state.lastError)
        }
    }

    private fun assertChallengeStaysStable(afterChallenge: AfterChallenge) {
        withClient(afterChallenge) { client, server ->
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            Thread.sleep(RETRY_WINDOW_MILLIS)
            val state = client.clientState.value
            assertEquals(1, server.authHeaders.size)
            assertEquals(ConnectionState.Failed, state.connectionState)
            assertNotNull(state.twoFactorChallenge)
            assertEquals(UiText.Resource(R.string.runtime_auth_two_factor_required), state.lastError)
        }
    }

    private fun withClient(afterChallenge: AfterChallenge, block: (MeshCentralClient, SignInFixture) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("two-factor-test-$name", mode)
        }
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = SignInFixture(afterChallenge)
        try {
            block(client, server)
        } finally {
            client.close()
            scope.cancel()
            vault.clearCredentials()
            server.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for connection state", condition())
    }

    private enum class AfterChallenge { EmptyCloseFrame, DropConnection }

    /** Minimal RFC 6455 endpoint for `control.ashx`; records each decoded `x-meshauth` header. */
    private class SignInFixture(private val afterChallenge: AfterChallenge) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val authHeaders = CopyOnWriteArrayList<List<String>>()
        val url: String get() = "http://127.0.0.1:${serverSocket.localPort}/"

        init {
            thread(isDaemon = true, name = "two-factor-fixture") {
                while (true) {
                    val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    sockets += socket
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        private fun serve(socket: Socket) = socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readHeaders(input)
            val auth = headers.getValue("x-meshauth").split(',')
                .map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
            authHeaders += auth
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${acceptKey(headers.getValue("sec-websocket-key"))}\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            if (auth[1] == PASSWORD && auth.size == 3 && auth[2] == VALID_CODE) {
                output.write(textFrame("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture","name":"test-user"}}"""))
                output.write(textFrame("""{"action":"nodes","nodes":{}}"""))
                output.flush()
            } else {
                // webserver.js: ws.send({action:'close', cause:'noauth', msg:...}); ws.close();
                output.write(
                    textFrame(
                        if (auth[1] != PASSWORD) {
                            """{"action":"close","cause":"noauth","msg":"noauth-2a"}"""
                        } else {
                            """{"action":"close","cause":"noauth","msg":"tokenrequired","email2fa":false,""" +
                                """"sms2fa":false,"msg2fa":false,"twoFactorCookieDays":30}"""
                        },
                    ),
                )
                when (afterChallenge) {
                    AfterChallenge.EmptyCloseFrame -> output.write(byteArrayOf(0x88.toByte(), 0))
                    AfterChallenge.DropConnection -> Unit
                }
                output.flush()
                if (afterChallenge == AfterChallenge.DropConnection) socket.shutdownOutput()
            }
            // Drain until the client leaves, so its own frames never meet a reset connection.
            while (input.read() != -1) Unit
        }

        private fun readHeaders(input: InputStream): Map<String, String> {
            val bytes = ByteArrayOutputStream()
            var tail = 0
            while (tail != END_OF_HEADERS) {
                val next = input.read()
                check(next >= 0) { "Request ended before its headers" }
                bytes.write(next)
                tail = (tail shl 8) or next
            }
            return bytes.toString("US-ASCII").split("\r\n").drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        }

        private fun acceptKey(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
        )

        /** Server frames are unmasked; every fixture message is shorter than 64 KiB. */
        private fun textFrame(text: String): ByteArray {
            val payload = text.toByteArray(Charsets.UTF_8)
            val header = if (payload.size < 126) {
                byteArrayOf(0x81.toByte(), payload.size.toByte())
            } else {
                byteArrayOf(0x81.toByte(), 126, (payload.size shr 8).toByte(), payload.size.toByte())
            }
            return header + payload
        }

        override fun close() {
            runCatching { serverSocket.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val PASSWORD = "test-password"
        const val VALID_CODE = "123456"

        /** Longer than the first automatic retry delay: a retry would open a second connection. */
        const val RETRY_WINDOW_MILLIS = 2_500L
        const val END_OF_HEADERS = 0x0D0A0D0A
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
