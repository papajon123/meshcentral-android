package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.json.JSONObject
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.LoginScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * MeshCentral answers a password-only `x-meshauth` header with a `tokenrequired` close message and
 * `ws.close()`: an empty close frame that OkHttp reports as 1005. MockWebServer cannot send that
 * frame, so a small socket fixture reproduces the server's exact bytes.
 */
class TwoFactorSignInTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loginTokenPairAuthenticatesWithoutSecondFactorOrRememberCookie() {
        withClient { client, server, vault ->
            server.acceptLoginToken = true
            client.login(server.url, TOKEN_USER, PASSWORD)
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            assertNull(client.clientState.value.twoFactorChallenge)
            assertEquals(listOf(TOKEN_USER, PASSWORD), server.authHeaders.single())
            assertTrue(server.cookieRequests.isEmpty())
            assertEquals(TOKEN_USER, vault.getCredentials(server.url)?.username)
            client.logout(clearStoredCredentials = false)
            assertTrue(client.loginWithStoredCredentials(server.url))
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            assertEquals(2, server.authHeaders.size)
        }
    }

    @Test fun profileAccessKeyAllowsLoginTokenOnProtectedServer() {
        withClient { client, server, vault ->
            server.acceptLoginToken = true
            server.requiredLoginKey = "synthetic key+&=?"
            vault.saveScopedSecret("login_key", server.url, server.requiredLoginKey!!)
            client.login(server.url, TOKEN_USER, PASSWORD)
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            assertNull(client.clientState.value.twoFactorChallenge)
            assertEquals(listOf(TOKEN_USER, PASSWORD), server.authHeaders.single())
        }
    }

    @Test fun missingAccessKeyDoesNotBecomeATwoFactorChallengeOrRetryLoop() {
        withClient { client, server, _ ->
            server.requiredLoginKey = "synthetic-required-key"
            client.login(server.url, TOKEN_USER, PASSWORD)
            await { client.clientState.value.connectionState == ConnectionState.Failed }
            Thread.sleep(RETRY_WINDOW_MILLIS)
            assertEquals(1, server.authHeaders.size)
            assertNull(client.clientState.value.twoFactorChallenge)
            assertEquals(MeshAuthDiagnostics.describe(MeshAuthFailure.AccessKeyRequired), client.clientState.value.lastError)
        }
    }

    @Test fun accessKeyIsNotSentToAnotherEndpoint() {
        withClient { client, first, vault ->
            first.acceptLoginToken = true
            first.requiredLoginKey = "syntheticKey"
            vault.saveLoginKey(first.url, first.requiredLoginKey!!)
            client.login(first.url, TOKEN_USER, PASSWORD)
            await { client.clientState.value.devicesCurrent }
            SignInFixture(AfterChallenge.EmptyCloseFrame).use { other ->
                other.acceptLoginToken = true
                client.login(other.url, TOKEN_USER, PASSWORD)
                await { client.clientState.value.devicesCurrent }
                assertEquals(listOf("/control.ashx"), other.requestTargets.toList())
            }
        }
    }

    @Test fun rememberedDeviceStillWorksWhenControlRequiresAnAccessKey() {
        withClient { client, server, vault ->
            server.requiredLoginKey = "syntheticKey"
            vault.saveLoginKey(server.url, server.requiredLoginKey!!)
            remember(client, server)
            client.close()
            withNewClient(vault) { next ->
                assertTrue(next.loginWithStoredCredentials(server.url))
                await { !next.clientState.value.requiresSignIn && next.clientState.value.devicesCurrent }
                assertNull(next.clientState.value.twoFactorChallenge)
                assertTrue(server.rememberCookieHeaders.last())
                assertEquals(2, server.authHeaders.last().size)
            }
        }
    }
    @Test fun emptyCloseFrameKeepsTheCodeStepWithoutReconnecting() {
        assertChallengeStaysStable(AfterChallenge.EmptyCloseFrame)
    }

    @Test fun transportDropAfterTheChallengeDoesNotReconnect() {
        assertChallengeStaysStable(AfterChallenge.DropConnection)
    }

    @Test fun codeFromTheChallengeCompletesSignIn() {
        withClient(AfterChallenge.EmptyCloseFrame) { client, server, _ ->
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
        withClient(AfterChallenge.EmptyCloseFrame) { client, server, _ ->
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
        withClient(afterChallenge) { client, server, _ ->
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

    @Test fun rememberedDeviceSkipsCodeOnNextLogin() {
        withClient { client, server, vault ->
            remember(client, server)
            assertEquals(1, server.cookieRequests.size)
            val expiry = vault.rememberedDevice.get(server.url, "test-user")!!.expiresAt
            assertTrue(expiry in (System.currentTimeMillis() + 6 * 86_400_000L)..(System.currentTimeMillis() + 7 * 86_400_000L))
            client.close()
            val count = server.authHeaders.size
            withNewClient(vault) { next ->
                assertTrue(next.loginWithStoredCredentials(server.url))
                await { !next.clientState.value.requiresSignIn && next.clientState.value.devicesCurrent }
                Thread.sleep(RETRY_WINDOW_MILLIS)
                assertEquals(count + 1, server.authHeaders.size)
                assertEquals(2, server.authHeaders.last().size)
                assertTrue(server.rememberCookieHeaders.last())
                assertNull(next.clientState.value.twoFactorChallenge)
                assertEquals(1, server.cookieRequests.size)
            }
        }
    }

    @Test fun expiredRememberCookieFallsBackToCodeStep() {
        withClient { client, server, vault ->
            remember(client, server)
            server.acceptRememberedCookie = false
            val count = server.authHeaders.size
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            Thread.sleep(RETRY_WINDOW_MILLIS)
            assertEquals(count + 1, server.authHeaders.size)
            assertTrue(server.rememberCookieHeaders.last())
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
            assertNull(client.clientState.value.rememberedDeviceExpiresAt)
            assertEquals(ConnectionState.Failed, client.clientState.value.connectionState)
            assertTrue(client.clientState.value.requiresSignIn)
        }
    }

    @Test fun logoutForgetsRememberedDevice() {
        withClient { client, server, vault ->
            remember(client, server)
            client.logout(clearStoredCredentials = false)
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
            assertTrue(client.loginWithStoredCredentials(server.url))
            await { client.clientState.value.twoFactorChallenge != null }
            assertFalse(server.rememberCookieHeaders.last())
        }
    }

    @Test fun rememberCookieIsNotSentToAnotherServer() {
        withClient { client, first, vault ->
            remember(client, first)
            SignInFixture(AfterChallenge.EmptyCloseFrame).use { second ->
                client.login(second.url, "test-user", PASSWORD)
                await { client.clientState.value.twoFactorChallenge != null }
                assertFalse(second.rememberCookieHeaders.single())
                assertNull(vault.rememberedDevice.get(first.url, "test-user"))
                client.login(first.url, "test-user", PASSWORD)
                await { client.clientState.value.twoFactorChallenge != null }
                assertFalse(first.rememberCookieHeaders.last())
            }
        }
    }

    @Test fun uncheckedBoxDoesNotRequestCookie() {
        withClient { client, server, vault ->
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            compose.setContent {
                MeshCentralMobileTheme { LoginScreen(client, vault, server.url) }
            }
            compose.onNodeWithTag("remember-device-checkbox").performScrollTo().assertIsOn().performClick().assertIsOff()
            compose.onNodeWithText(compose.activity.getString(R.string.login_two_factor_code)).performTextInput(VALID_CODE)
            compose.onNodeWithText(compose.activity.getString(R.string.login_action_sign_in_with_code)).performScrollTo().performClick()
            try {
                await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            } catch (failure: AssertionError) {
                val state = client.clientState.value
                throw AssertionError("UI sign-in did not finish: attempts=${server.authHeaders.size}, " +
                    "state=${state.connectionState}, challenge=${state.twoFactorChallenge != null}, " +
                    "requiresSignIn=${state.requiresSignIn}, devicesCurrent=${state.devicesCurrent}", failure)
            }
            Thread.sleep(300)
            assertTrue(server.cookieRequests.isEmpty())
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    @Test fun badCredentialsEraseRememberedDevice() {
        withClient { client, server, vault ->
            remember(client, server)
            client.login(server.url, "test-user", "wrong-password")
            await { client.clientState.value.connectionState == ConnectionState.Failed }
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    @Test fun rememberCookieIsNotSentToAnotherUser() {
        withClient { client, server, vault ->
            remember(client, server)
            client.login(server.url, "other-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            assertFalse(server.rememberCookieHeaders.last())
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    @Test fun forgetActionCannotBeUndoneByALateCookieReply() {
        withClient { client, server, vault ->
            server.delayCookieReply = true
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            client.submitTwoFactorCode(VALID_CODE)
            await { server.cookieRequests.size == 1 }
            client.forgetRememberedDevice()
            server.sendDelayedCookie()
            Thread.sleep(300)
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    @Test fun serverCanDisableRememberingWithoutBreakingSignIn() {
        withClient { client, server, vault ->
            server.cookieDays = 0
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            assertEquals(0, client.clientState.value.twoFactorChallenge!!.twoFactorCookieDays)
            client.submitTwoFactorCode(VALID_CODE)
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            Thread.sleep(300)
            assertTrue(server.cookieRequests.isEmpty())
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    @Test fun silentCookieRefusalDoesNotBlockAnAcceptedSignIn() {
        withClient { client, server, vault ->
            server.delayCookieReply = true
            client.login(server.url, "test-user", PASSWORD)
            await { client.clientState.value.twoFactorChallenge != null }
            client.submitTwoFactorCode(VALID_CODE)
            await { !client.clientState.value.requiresSignIn && client.clientState.value.devicesCurrent }
            await { server.cookieRequests.size == 1 }
            Thread.sleep(300)
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
            assertNull(client.clientState.value.twoFactorChallenge)
            assertEquals(2, server.authHeaders.size)
        }
    }

    @Test fun codeDeliveryMarkersNeverRequestARememberCookie() {
        withClient { client, server, vault ->
            for (channel in TwoFactorChannel.entries) {
                val count = server.authHeaders.size
                client.login(server.url, "test-user", PASSWORD, channel.requestToken)
                await { server.authHeaders.size == count + 1 && client.clientState.value.twoFactorChallenge != null }
            }
            assertTrue(server.cookieRequests.isEmpty())
            assertNull(vault.rememberedDevice.get(server.url, "test-user"))
        }
    }

    private fun remember(client: MeshCentralClient, server: SignInFixture) {
        client.login(server.url, "test-user", PASSWORD)
        await { client.clientState.value.twoFactorChallenge != null }
        client.submitTwoFactorCode(VALID_CODE)
        await { client.clientState.value.rememberedDeviceExpiresAt != null }
    }

    private fun withNewClient(vault: SecureVault, block: (MeshCentralClient) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = MeshCentralClient(vault, scope)
        try { block(client) } finally { client.close(); scope.cancel() }
    }

    private fun withClient(afterChallenge: AfterChallenge = AfterChallenge.EmptyCloseFrame,
        block: (MeshCentralClient, SignInFixture, SecureVault) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("two-factor-test-$name", mode)
        }
        val vault = SecureVault(isolated)
        vault.clearCredentials()
        val client = MeshCentralClient(vault, scope)
        val server = SignInFixture(afterChallenge)
        try {
            block(client, server, vault)
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
        val requestTargets = CopyOnWriteArrayList<String>()
        val rememberCookieHeaders = CopyOnWriteArrayList<Boolean>()
        val cookieRequests = CopyOnWriteArrayList<JSONObject>()
        @Volatile var acceptRememberedCookie = true
        @Volatile var delayCookieReply = false
        @Volatile var cookieDays = 7
        @Volatile var acceptLoginToken = false
        @Volatile var requiredLoginKey: String? = null
        @Volatile private var pendingCookieSocket: Socket? = null
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
            requestTargets += headers.getValue(":target")
            val auth = headers.getValue("x-meshauth").split(',')
                .map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
            authHeaders += auth
            val hasCookie = headers["cookie"]?.split("; ")?.contains("twofactor=$REMEMBER_COOKIE") == true
            rememberCookieHeaders += hasCookie
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${acceptKey(headers.getValue("sec-websocket-key"))}\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII),
            )
            val suppliedKey = ("http://localhost" + headers.getValue(":target")).toHttpUrl().queryParameter("key")
            if (requiredLoginKey != null && suppliedKey != requiredLoginKey) {
                output.write(textFrame("""{"action":"close","cause":"noauth","msg":"nokey"}"""))
                output.write(byteArrayOf(0x88.toByte(), 0))
                output.flush()
            } else if (auth[1] == PASSWORD && ((acceptLoginToken && auth[0] == TOKEN_USER && auth.size == 2) ||
                (auth.size == 3 && auth[2] == VALID_CODE) ||
                (hasCookie && acceptRememberedCookie && auth[0] == "test-user"))) {
                output.write(textFrame("""{"action":"userinfo","userinfo":{"_id":"user/test/fixture","name":"test-user"}}"""))
                output.write(textFrame("""{"action":"nodes","nodes":{}}"""))
                output.flush()
                while (true) {
                    val command = readClientMessage(input) ?: break
                    if (command.optString("action") == "twoFactorCookie") {
                        cookieRequests += command
                        if (delayCookieReply) pendingCookieSocket = socket else sendCookie(socket)
                    }
                }
                return@use
            } else {
                // webserver.js: ws.send({action:'close', cause:'noauth', msg:...}); ws.close();
                output.write(
                    textFrame(
                        if (auth[1] != PASSWORD) {
                            """{"action":"close","cause":"noauth","msg":"noauth-2a"}"""
                        } else {
                            """{"action":"close","cause":"noauth","msg":"tokenrequired","email2fa":false,""" +
                                """"sms2fa":false,"msg2fa":false,"twoFactorCookieDays":$cookieDays}"""
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

        fun sendDelayedCookie() { pendingCookieSocket?.let(::sendCookie) }

        private fun sendCookie(socket: Socket) {
            socket.getOutputStream().apply {
                write(textFrame("""{"action":"twoFactorCookie","cookie":"$REMEMBER_COOKIE"}"""))
                flush()
            }
        }

        private fun readClientMessage(input: InputStream): JSONObject? {
            val first = input.read()
            if (first == -1 || first and 0x0f == 8) return null
            val second = input.read()
            if (second == -1) return null
            var length = second and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            check(length in 0..65535)
            val mask = if (second and 0x80 != 0) ByteArray(4) { input.read().toByte() } else null
            val payload = ByteArray(length) { index ->
                val byte = input.read()
                check(byte >= 0)
                (byte xor (mask?.get(index % 4)?.toInt() ?: 0)).toByte()
            }
            return runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrDefault(JSONObject())
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
            val lines = bytes.toString("US-ASCII").split("\r\n")
            return lines.drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() } +
                (":target" to lines.first().split(' ')[1])
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
        const val TOKEN_USER = "~t:SyntheticMixedCASE"
        const val VALID_CODE = "123456"
        const val REMEMBER_COOKIE = "synthetic-remember-cookie"

        /** Longer than the first automatic retry delay: a retry would open a second connection. */
        const val RETRY_WINDOW_MILLIS = 2_500L
        const val END_OF_HEADERS = 0x0D0A0D0A
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
