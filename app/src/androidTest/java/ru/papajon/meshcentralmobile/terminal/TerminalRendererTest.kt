package ru.papajon.meshcentralmobile.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.session.*
import ru.papajon.meshcentralmobile.ui.screens.TerminalScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real bundled WebView, with a synthetic in-memory relay. No live server or account. */
class TerminalRendererTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val relay = FakeRelay()
    private lateinit var controller: TerminalController
    private lateinit var renderer: TerminalWebRenderer
    private var providerMajor: Int? = null
    private lateinit var providerReport: JSONObject

    @Before fun recordRealWebViewProvider() {
        val provider = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        providerMajor = provider?.versionName?.let { version ->
            Regex("^(\\d+)(?:\\.|$)").find(version)?.groupValues?.get(1)?.toIntOrNull()
        }
        // Chromium 80 is only the syntax floor for ?. and ??. We have not certified version 80
        // itself; every provider at/above this floor still has to pass the unchanged happy tests.
        providerReport = JSONObject().put("test", testName.methodName).put("androidApi", Build.VERSION.SDK_INT)
            .put("providerPackage", provider?.packageName ?: JSONObject.NULL)
            .put("providerVersion", provider?.versionName ?: JSONObject.NULL)
            .put("providerMajor", providerMajor ?: JSONObject.NULL)
            .put("syntaxMinimumMajor", 80).put("version80Certified", false)
        recordProviderDecision("detected")
    }

    private fun recordProviderDecision(decision: String, elapsedMillis: Long? = null) {
        providerReport.put("decision", decision)
        elapsedMillis?.let { providerReport.put("elapsedMillis", it) }
        // Only public system-package/version metadata: no terminal data, URL, account or credential.
        File(compose.activity.cacheDir, "terminal-webview-${testName.methodName}.json")
            .writeText(providerReport.toString(2))
    }

    private fun requireHappyPathProvider() {
        val knownLegacySyntax = providerMajor?.let { it < 80 } == true
        val skip = Build.VERSION.SDK_INT < 35 && knownLegacySyntax
        recordProviderDecision(if (skip) "skip_known_legacy_syntax_on_older_android" else "run_required_happy_path")
        Assume.assumeFalse(
            "Real WebView ${providerReport.optString("providerPackage")} ${providerReport.optString("providerVersion")} " +
                "is below Chromium 80 syntax support on API ${Build.VERSION.SDK_INT}; covered by legacy error-path test",
            skip,
        )
        // Unknown/null versions and API >= 35 never qualify for this skip.
    }

    @After fun cleanup() {
        compose.runOnIdle { if (::controller.isInitialized) controller.close(); scope.cancel() }
    }

    private fun setup() {
        requireHappyPathProvider()
        compose.runOnIdle {
            controller = TerminalController(relay, scope)
            renderer = requireNotNull(controller.renderer(compose.activity))
            controller.connect()
        }
        compose.setContent { AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(15000) { evaluate("typeof window.meshTerminal") == "object" }
    }

    private fun evaluate(script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnUiThread { renderer.view.evaluateJavascript(script) {
            result = runCatching { JSONTokener(it).nextValue().toString() }.getOrDefault("")
            done.countDown()
        } }
        assertTrue("JavaScript callback completed", done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun ansiUtf8SelectionAndOscClipboardIsolation() {
        setup()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "unchanged")) }
        val stream = "abc\r\u001b[2KZ你好\u001b]52;c;Y2hhbmdlZA==\u0007".toByteArray(Charsets.UTF_8)
        stream.forEach { relay.frames.trySend(RelayFrame.Binary(byteArrayOf(it))) }
        compose.waitUntil(10000) {
            evaluate("window.meshTerminal.selectAll();window.meshTerminal.selection().trim()") == "Z你好"
        }
        compose.runOnIdle {
            assertEquals("unchanged", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            renderer.copySelection("test")
        }
        compose.waitUntil(5000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() == "Z你好" }
        assertTrue(relay.texts.any { it.contains("termsize") })
    }

    @Test fun actualInputConnectionCommitsCjkOnceAndDisconnectBlocksKeys() {
        setup()
        compose.runOnIdle { renderer.focus() }
        compose.waitUntil(5000) { evaluate("document.activeElement.tagName") == "TEXTAREA" }
        compose.runOnIdle {
            val input = renderer.view.onCreateInputConnection(EditorInfo())
            assertNotNull("WebView exposes the terminal IME input connection", input)
            assertTrue(input!!.commitText("你好", 1))
        }
        compose.waitUntil(5000) { relay.binary.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("你好", relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
            renderer.setControl(true)
            renderer.key("c")
        }
        compose.waitUntil(5000) { relay.binary.any { it.contentEquals(byteArrayOf(3)) } }
        val count = relay.binary.size
        compose.runOnIdle { relay.close(); renderer.key("\r") }
        evaluate("true") // barrier after the key script
        compose.runOnIdle { assertEquals(count, relay.binary.size) }
    }

    @Test fun compositionAndHardwareInputStaySingleWithAccessibleOutputEnabled() {
        setup()
        compose.runOnIdle { renderer.focus() }
        compose.waitUntil(5000) { evaluate("document.activeElement.tagName") == "TEXTAREA" }
        assertEquals("true", evaluate("document.querySelector('.xterm-accessibility') !== null"))
        compose.runOnIdle {
            val input = renderer.view.onCreateInputConnection(EditorInfo())!!
            assertTrue(input.setComposingText("中", 1))
            assertTrue(input.setComposingText("中文", 1))
            assertTrue(input.commitText("中文", 1))
        }
        compose.waitUntil(5000) { relay.binary.isNotEmpty() }
        evaluate("true")
        compose.runOnIdle {
            assertEquals("中文", relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
        }
        // Inject through Android's focused-window route, rather than an unsourced synthetic
        // dispatch directly to WebView (Chromium does not treat that as a hardware key).
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_A)
        compose.waitUntil(5000) { relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8).endsWith("a") }
        evaluate("true")
        compose.runOnIdle {
            assertEquals("中文a", relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
        }
    }

    @Test fun detachReattachKeepsTheSameTerminalAndNeverResendsInput() {
        requireHappyPathProvider()
        var visible by mutableStateOf(true)
        compose.runOnIdle {
            controller = TerminalController(relay, scope)
            renderer = requireNotNull(controller.renderer(compose.activity))
            controller.connect()
        }
        compose.setContent { if (visible) AndroidView(factory = { renderer.detach(); renderer.view }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(15000) { evaluate("typeof window.meshTerminal") == "object" }
        relay.frames.trySend(RelayFrame.Binary("retained".toByteArray()))
        compose.waitUntil(5000) { evaluate("window.meshTerminal.selectAll();window.meshTerminal.selection().trim()") == "retained" }
        compose.runOnIdle { controller.sendInput("once\r"); visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true; controller.connect() }
        compose.waitForIdle()
        assertEquals("retained", evaluate("window.meshTerminal.selectAll();window.meshTerminal.selection().trim()"))
        assertEquals(1, relay.connects)
        assertEquals(1, relay.binary.size)
    }

    @Test fun realLegacyProviderShowsUpdateInstructionWithinTwentySeconds() {
        val knownModern = providerMajor?.let { it >= 80 } == true
        recordProviderDecision(if (knownModern) "skip_legacy_error_path_on_modern_provider" else "run_real_legacy_error_path")
        Assume.assumeFalse(
            "Legacy error-path test requires a real WebView below Chromium 80; detected " +
                "${providerReport.optString("providerPackage")} ${providerReport.optString("providerVersion")}",
            knownModern,
        )
        // An unknown version cannot prove either compatibility or a real legacy-provider fixture.
        assertNotNull("Cannot verify the real legacy WebView fixture: provider version is unknown", providerMajor)
        val startedAt = SystemClock.elapsedRealtime()
        compose.runOnIdle { controller = TerminalController(relay, scope) }
        compose.setContent {
            MeshCentralMobileTheme { TerminalScreen(controller, onBack = {}) }
        }
        compose.waitUntil(19_000) { controller.state.value.phase == RelayPhase.Failed }
        compose.runOnIdle {
            assertEquals(UiText.Resource(R.string.terminal_webview_update_required), controller.state.value.message)
            assertFalse(controller.state.value.ready)
            assertEquals(RelayPhase.Closed, relay.state.value.phase)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_webview_update_required)).assertIsDisplayed()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        recordProviderDecision("legacy_update_instruction_visible", elapsed)
        assertTrue("Legacy provider must show the exact update instruction within 20 seconds (elapsed=$elapsed)", elapsed <= 20_000)
    }

    private class FakeRelay : RelayConnection {
        override val protocol = RelayProtocol.AgentTerminal
        override val state = MutableStateFlow(RelayStatus())
        val frames = Channel<RelayFrame>(1024)
        override val incoming = frames.receiveAsFlow()
        val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val binary = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        var connects = 0
        override fun connect() { connects++; state.value = RelayStatus(RelayPhase.Connected) }
        override fun sendText(value: String) = (state.value.phase == RelayPhase.Connected).also { if (it) texts.add(value) }
        override fun sendBinary(value: ByteArray) = (state.value.phase == RelayPhase.Connected).also { if (it) binary.add(value) }
        override fun close() { state.value = RelayStatus(RelayPhase.Closed) }
    }
}
