package ru.papajon.meshcentralmobile.terminal

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ru.papajon.meshcentralmobile.ui.screens.TerminalScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.localization.UiText
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A real bundled WebView with synthetic relays: reconnect keeps the view but not the session. */
class TerminalRendererReconnectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: TerminalController
    private lateinit var renderer: TerminalWebRenderer
    private val next = ArrayDeque<FakeRelay>()
    @Volatile private var factoryCalls = 0

    @After fun cleanup() {
        compose.runOnIdle {
            if (::controller.isInitialized) controller.close()
            scope.cancel()
        }
    }

    private fun requireModernProvider() {
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val major = provider?.versionName?.let { version ->
            Regex("^(\\d+)(?:\\.|$)").find(version)?.groupValues?.get(1)?.toIntOrNull()
        }
        Assume.assumeFalse(
            "Reconnect needs Chromium 80 syntax support; detected ${provider?.packageName} ${provider?.versionName}",
            major != null && major < 80,
        )
    }

    private fun start(protocol: RelayProtocol, vararg reconnects: FakeRelay, fullScreen: Boolean = false): FakeRelay {
        requireModernProvider()
        next.addAll(reconnects.toList())
        val first = FakeRelay(protocol)
        compose.runOnIdle {
            controller = TerminalController(first, scope, connectionFactory = {
                factoryCalls++
                next.pollFirst()
            })
            renderer = requireNotNull(controller.renderer(compose.activity))
            controller.connect()
        }
        compose.setContent {
            if (fullScreen) MeshCentralMobileTheme { TerminalScreen(controller, {}) }
            else AndroidView(factory = renderer::createHost, onRelease = renderer::releaseHost,
                modifier = Modifier.fillMaxSize())
        }
        awaitTerminalPage()
        return first
    }

    private fun awaitTerminalPage() {
        compose.waitUntil(15000) {
            var loaded = false
            compose.runOnUiThread {
                loaded = renderer.view.url == "https://terminal.meshcentral.invalid/index.html" &&
                    renderer.view.progress == 100
            }
            loaded && evaluate("typeof window.meshTerminal") == "object"
        }
    }

    private fun evaluate(script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            renderer.view.evaluateJavascript(script) {
                result = runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault("")
                done.countDown()
            }
        }
        assertTrue("JavaScript callback completed", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun viewport(): JSONObject = JSONObject(evaluate("JSON.stringify(window.meshTerminal.viewport())"))

    private fun selection(): String = evaluate("window.meshTerminal.selectAll(); window.meshTerminal.selection()")

    private fun awaitText(fragment: String) {
        compose.waitUntil(10000) { selection().contains(fragment) }
    }

    private fun credentialFrames(relay: FakeRelay): Int = relay.texts.count {
        runCatching { JSONObject(it).optString("action") }.getOrDefault("") in setOf("sshauth", "sshkeyauth")
    }

    @Test fun reconnectKeepsSameRendererScrollbackAndViewport() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val first = start(RelayProtocol.AgentTerminal, second)
        first.write((1..120).joinToString("\r\n") { "line-$it" } + "\r\n")
        awaitText("line-120")

        evaluate("window.meshTerminal.scrollToLine(3); true")
        val before = viewport()
        val viewportLine = before.getInt("y")
        assertTrue("Fixture must be scrolled away from the bottom", viewportLine < before.getInt("base"))
        val marker = compose.activity.getString(R.string.terminal_new_session)

        compose.runOnIdle {
            controller.disconnect()
            assertTrue(controller.state.value.canReconnect)
            assertTrue(controller.reconnect(marker))
        }
        compose.waitUntil(10000) { second.connects == 1 }
        compose.waitUntil(10000) { evaluate("window.meshTerminal.sessionSerial") == "1" }

        assertSame("The same WebView must serve the new session", renderer,
            controller.renderer(compose.activity))
        val after = viewport()
        assertEquals("The same scrollback line must stay in view after a reconnect",
            viewportLine, after.getInt("y"))
        val retained = selection()
        assertTrue(retained.contains("line-1"))
        assertTrue(retained.contains("line-120"))
        assertTrue("The session marker is a visible part of local history", retained.contains(marker))

        second.write("fresh-session\r\n")
        awaitText("fresh-session")
        assertTrue("New output must not discard old scrollback", selection().contains("line-120"))
        assertEquals(1, second.connects)
        assertTrue(first.closed)
        assertTrue(second.binary.isEmpty())
    }

    @Test fun reconnectButtonAndErrorBannerKeepTheViewport() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val first = start(RelayProtocol.AgentTerminal, second, fullScreen = true)
        first.write((1..120).joinToString("\r\n") { "line-$it" } + "\r\n")
        awaitText("line-120")
        evaluate("window.meshTerminal.scrollToLine(3); true")
        assertEquals(3, viewport().getInt("y"))
        compose.runOnIdle { first.state.value = RelayStatus(RelayPhase.Failed, UiText.Literal("Synthetic network disconnected")) }
        compose.waitUntil(5000) { controller.state.value.canReconnect }
        compose.waitForIdle()
        assertTrue(selection().contains("line-120"))
        assertEquals("Showing the error must not move scrollback", 3, viewport().getInt("y"))
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_reconnect)).performClick()
        compose.waitUntil(10000) { evaluate("window.meshTerminal.sessionSerial") == "1" }
        assertEquals("Restoring the terminal height must preserve scrollback", 3, viewport().getInt("y"))
        assertTrue(selection().contains("line-120"))
    }

    @Test fun newShellClearingItsScreenKeepsOldTailAndSessionMarker() {
        val second = FakeRelay(RelayProtocol.AgentTerminal)
        val first = start(RelayProtocol.AgentTerminal, second)
        first.write((1..120).joinToString("\r\n") { "line-$it" } + "\r\n")
        awaitText("line-120")
        val marker = compose.activity.getString(R.string.terminal_new_session)
        compose.runOnIdle { controller.disconnect(); assertTrue(controller.reconnect(marker)) }
        compose.waitUntil(10000) { evaluate("window.meshTerminal.sessionSerial") == "1" }
        second.write("\u001b[H\u001b[2J\u001b[3Jfresh-shell\r\n")
        awaitText("fresh-shell")
        val retained = selection()
        assertTrue("Old screen tail must be scrollback before the new shell can erase its screen", retained.contains("line-120"))
        assertTrue(retained.contains(marker))
        compose.runOnIdle { assertTrue(controller.sendInput("clear\r")) }
        second.write("\u001b[H\u001b[2J\u001b[3Jafter-explicit-clear\r\n")
        awaitText("after-explicit-clear")
        assertFalse("A clear after explicit input must erase retained history", selection().contains("line-120"))
    }

    @Test fun sshReconnectAsksForCredentialsAgainAndNeverReplaysThem() {
        val second = FakeRelay(RelayProtocol.SshTerminal)
        val first = start(RelayProtocol.SshTerminal, second)
        first.receiveText("{\"action\":\"sshauth\"}")
        compose.waitUntil(5000) { controller.state.value.auth == TerminalAuth.PasswordOrKey }
        compose.runOnIdle { controller.authenticate("synthetic-user", "synthetic-password") }
        compose.waitUntil(5000) { first.texts.size == 1 }
        first.receiveText("c")
        first.receiveText("~synthetic-prompt$ ")
        compose.waitUntil(5000) { controller.state.value.ready }

        val marker = compose.activity.getString(R.string.terminal_new_session)
        compose.runOnIdle {
            controller.disconnect()
            assertTrue(controller.reconnect(marker))
        }
        compose.waitUntil(10000) { second.connects == 1 }
        compose.waitUntil(10000) { evaluate("window.meshTerminal.sessionSerial") == "1" }

        assertSame(renderer, controller.renderer(compose.activity))
        assertEquals(TerminalAuth.None, controller.state.value.auth)
        assertEquals("Credentials must not be replayed into the fresh relay", 0, credentialFrames(second))
        second.receiveText("{\"action\":\"sshauth\"}")
        compose.waitUntil(5000) { controller.state.value.auth == TerminalAuth.PasswordOrKey }
        assertEquals("Opening the prompt must not submit anything", 0, credentialFrames(second))

        compose.runOnIdle { controller.authenticate("synthetic-user-2", "synthetic-password-2") }
        compose.waitUntil(5000) { credentialFrames(second) == 1 }
        assertEquals("sshauth", JSONObject(second.texts.last { it.contains("sshauth") }).getString("action"))
        assertEquals("The old relay received no second credential submission", 1, credentialFrames(first))
        assertEquals(1, factoryCalls)
    }

    private class FakeRelay(override val protocol: RelayProtocol) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(1024)
        override val incoming = frames.receiveAsFlow()
        val texts = CopyOnWriteArrayList<String>()
        val binary = CopyOnWriteArrayList<ByteArray>()
        @Volatile var connects = 0
        @Volatile var closed = false

        override fun connect() { connects++; state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = (state.value.phase == RelayPhase.Connected).also {
            if (it) texts.add(value)
        }
        override fun sendBinary(value: ByteArray) = (state.value.phase == RelayPhase.Connected).also {
            if (it) binary.add(value)
        }
        override fun close() { closed = true; state.value = RelayStatus(RelayPhase.Closed) }
        fun receiveText(value: String) { check(frames.trySend(RelayFrame.Text(value)).isSuccess) }
        fun write(value: String) { check(frames.trySend(RelayFrame.Binary(value.toByteArray())).isSuccess) }
    }
}
