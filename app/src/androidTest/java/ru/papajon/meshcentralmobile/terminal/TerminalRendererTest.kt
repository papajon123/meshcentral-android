package ru.papajon.meshcentralmobile.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.InputDevice
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.json.JSONArray
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
    private var capturedEditorType = InputType.TYPE_NULL
    private var capturedConnectionClass: String? = null
    private var capturedSelectionKnown = false
    private var capturedSelectionStart = -1
    private var capturedSelectionEnd = -1
    private var capturedConnectionIdentity: Int? = null
    private var reportedDomEvents = 0

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
            .put("keyboardFixture", keyboardFixtureState())
        recordProviderDecision("detected")
    }

    private fun recordProviderDecision(decision: String, elapsedMillis: Long? = null) {
        providerReport.put("decision", decision)
        elapsedMillis?.let { providerReport.put("elapsedMillis", it) }
        // Only public system-package/version metadata: no terminal data, URL, account or credential.
        File(compose.activity.cacheDir, "terminal-webview-${testName.methodName}.json")
            .writeText(providerReport.toString(2))
        if (decision != "ime_evidence_recorded") {
            val summary = JSONObject(providerReport.toString()).apply { remove("imeChecks") }
            Log.i("TerminalWebViewTest", summary.toString())
        }
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
        compose.setContent { AndroidView(factory = renderer::createHost, onRelease = renderer::releaseHost, modifier = Modifier.fillMaxSize()) }
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

    private fun installImeEventProbe() {
        evaluate("""
            window.terminalTestImeEvents=[];
            ['focusin','focusout','beforeinput','input','compositionstart','compositionupdate','compositionend','keydown','keypress','keyup'].forEach(type =>
              document.addEventListener(type, event => {
                if(window.terminalTestImeEvents.length<40) window.terminalTestImeEvents.push({
                  type:event.type,at:Math.round(performance.now()),inputType:event.inputType,
                  dataLength:event.data?.length,composing:event.isComposing,keyCode:event.keyCode,
                  code:event.code,shift:event.shiftKey,ctrl:event.ctrlKey,alt:event.altKey,
                  targetIsTextarea:event.target?.tagName==='TEXTAREA'});
              },true)); true
        """.trimIndent())
    }

    private fun nativeImeState(): JSONObject {
        lateinit var result: JSONObject
        compose.runOnIdle {
            val view = renderer.view
            val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            result = JSONObject().put("attached", view.isAttachedToWindow).put("nativeFocus", view.hasFocus())
                .put("windowFocus", view.hasWindowFocus()).put("activityFocusMatches", compose.activity.currentFocus === view)
                .put("nativeTextEditor", view.onCheckIsTextEditor()).put("immActiveForView", imm.isActive(view))
                .put("imeVisible", ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                .put("layoutPending", view.isLayoutRequested || view.rootView.isLayoutRequested)
                .put("controllerReady", controller.state.value.ready)
                .put("capturedEditorType", capturedEditorType).put("capturedSelectionKnown", capturedSelectionKnown)
                .put("capturedSelectionStart", capturedSelectionStart).put("capturedSelectionEnd", capturedSelectionEnd)
                .put("capturedConnectionIdentity", capturedConnectionIdentity ?: JSONObject.NULL)
                .put("capturedConnectionClass", capturedConnectionClass ?: JSONObject.NULL)
                .put("keyboardFixture", keyboardFixtureState())
        }
        return result
    }

    private fun keyboardFixtureState(): JSONObject {
        val configuration = compose.activity.resources.configuration
        val keyboards = JSONArray()
        InputDevice.getDeviceIds().forEach { id ->
            val device = InputDevice.getDevice(id)
            if (device != null && device.supportsSource(InputDevice.SOURCE_KEYBOARD)) {
                // Do not record device names, descriptors or Bluetooth addresses.
                keyboards.put(JSONObject().put("id", id).put("sources", device.sources)
                    .put("keyboardType", device.keyboardType).put("isVirtual", device.isVirtual))
            }
        }
        return JSONObject().put("keyboard", configuration.keyboard).put("keyboardHidden", configuration.keyboardHidden)
            .put("hardKeyboardHidden", configuration.hardKeyboardHidden).put("keyboardDevices", keyboards)
    }

    private fun recordImeEvidence(stage: String, logFailure: Boolean = false) {
        val native = nativeImeState()
        native.put("stage", stage).put("uptimeMillis", SystemClock.uptimeMillis())
        native.put("dom", JSONObject(evaluate("""
            (() => {const field=document.activeElement.tagName==='TEXTAREA'?document.activeElement:document.querySelector('.xterm-helper-textarea');
              const rect=field?.getBoundingClientRect();const style=field?getComputedStyle(field):null;
              return JSON.stringify({focused:document.hasFocus(),activeIsTextarea:document.activeElement.tagName==='TEXTAREA',
                disabled:document.activeElement.disabled===true,readOnly:document.activeElement.readOnly===true,
                textareaLength:field?.value.length,selectionStart:field?.selectionStart,selectionEnd:field?.selectionEnd,
                selectionDirection:field?.selectionDirection,caretRect:rect?{x:rect.x,y:rect.y,width:rect.width,height:rect.height}:null,
                opacity:style?.opacity,visibility:style?.visibility,display:style?.display,
                events:window.terminalTestImeEvents||[]});})()
        """.trimIndent())))
        val received = relay.binary.flatMap { it.toList() }.toByteArray()
        native.put("receivedFrames", relay.binary.size).put("receivedBytes", received.size)
            .put("suffixIsLowerA", received.lastOrNull() == 'a'.code.toByte())
            .put("suffixIsUpperA", received.lastOrNull() == 'A'.code.toByte())
            .put("exactExpectedLowercaseFixture", received.contentEquals("中文a".toByteArray(Charsets.UTF_8)))
        val checks = providerReport.optJSONArray("imeChecks") ?: JSONArray().also { providerReport.put("imeChecks", it) }
        checks.put(native)
        recordProviderDecision("ime_evidence_recorded")
        if (!logFailure) return
        // AGP can uninstall the app after the suite. Keep the same safe evidence in archived
        // per-test logcat; separate event records avoid Android's per-log-entry size truncation.
        val summary = JSONObject(native.toString())
        val dom = summary.getJSONObject("dom")
        val events = dom.getJSONArray("events")
        dom.remove("events")
        summary.put("test", testName.methodName).put("eventCount", events.length())
        Log.i("TerminalImeEvidence", summary.toString())
        while (reportedDomEvents < events.length()) {
            val index = reportedDomEvents++
            val event = JSONObject(events.getJSONObject(index).toString()).put("test", testName.methodName)
                .put("stage", stage).put("eventIndex", index)
            Log.i("TerminalImeEvent", event.toString())
        }
    }

    /** Waits for Android's native editor/served view, not just Chromium's earlier DOM focus event. */
    private fun readyInputConnection(stage: String): InputConnection {
        var ready: InputConnection? = null
        compose.runOnIdle { renderer.focus() } // One focus request; no input or keyboard retry.
        try {
            compose.waitUntil(5000) {
                if (evaluate("document.hasFocus() && document.activeElement.tagName==='TEXTAREA' && !document.activeElement.disabled && !document.activeElement.readOnly") != "true") {
                    false
                } else {
                    val snapshot = nativeImeState()
                    val nativeReady = snapshot.getBoolean("attached") && snapshot.getBoolean("nativeFocus") &&
                        snapshot.getBoolean("windowFocus") && snapshot.getBoolean("nativeTextEditor") &&
                        snapshot.getBoolean("immActiveForView") && snapshot.getBoolean("imeVisible") &&
                        !snapshot.getBoolean("layoutPending") && snapshot.getBoolean("controllerReady")
                    if (nativeReady) compose.runOnIdle {
                        val editor = EditorInfo()
                        val connection = renderer.view.onCreateInputConnection(editor)
                        capturedEditorType = editor.inputType
                        capturedSelectionKnown = editor.initialSelStart >= 0 && editor.initialSelEnd >= 0
                        capturedSelectionStart = editor.initialSelStart
                        capturedSelectionEnd = editor.initialSelEnd
                        capturedConnectionClass = connection?.javaClass?.simpleName
                        capturedConnectionIdentity = connection?.let { System.identityHashCode(it) }
                        if (connection != null && (editor.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && capturedSelectionKnown) {
                            ready = connection
                        }
                    }
                    ready != null
                }
            }
        } catch (failure: Throwable) {
            recordImeEvidence("$stage-readiness-failed", logFailure = true)
            throw failure
        }
        recordImeEvidence("$stage-input-connection-ready")
        assertEquals("The native editor must be paint-transparent and must not intercept terminal touches", "true", evaluate("""
            (() => {const s=getComputedStyle(document.querySelector('.xterm-helper-textarea'));
              return s.opacity==='1' && s.pointerEvents==='none' && s.outlineStyle==='none' &&
                [s.color,s.backgroundColor,s.caretColor].every(color=>color==='rgba(0, 0, 0, 0)');})()
        """.trimIndent()))
        return requireNotNull(ready)
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
        installImeEventProbe()
        val input = readyInputConnection("direct-commit")
        compose.runOnIdle {
            assertNotNull("WebView exposes the terminal IME input connection", input)
            assertTrue(input.commitText("你好", 1))
        }
        try { compose.waitUntil(5000) { relay.binary.isNotEmpty() } }
        catch (failure: Throwable) { recordImeEvidence("direct-commit-failed", logFailure = true); throw failure }
        recordImeEvidence("after-direct-commit")
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

    private fun commitAndVerifyCjkComposition(input: InputConnection) {
        compose.runOnIdle {
            assertTrue(input.setComposingText("中", 1))
            assertTrue(input.setComposingText("中文", 1))
            assertTrue(input.commitText("中文", 1))
        }
        compose.waitUntil(5000) { relay.binary.isNotEmpty() }
        evaluate("true")
        compose.runOnIdle {
            assertEquals("中文", relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
        }
    }

    private fun verifySingleKeyAfterComposition(stage: String) {
        try { compose.waitUntil(5000) { relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8).endsWith("a") } }
        catch (failure: Throwable) { recordImeEvidence("$stage-failed", logFailure = true); throw failure }
        recordImeEvidence(stage)
        evaluate("true")
        compose.runOnIdle {
            assertEquals("中文a", relay.binary.flatMap { it.toList() }.toByteArray().toString(Charsets.UTF_8))
        }
    }

    @Test fun compositionAndImeKeyEventStaySingleWithAccessibleOutputEnabled() {
        setup()
        installImeEventProbe()
        val input = readyInputConnection("composition")
        assertEquals("true", evaluate("document.querySelector('.xterm-accessibility') !== null"))
        commitAndVerifyCjkComposition(input)
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD
        providerReport.put("injection", JSONObject().put("api", "InputConnection.sendKeyEvent")
            .put("deviceId", KeyCharacterMap.VIRTUAL_KEYBOARD).put("source", InputDevice.SOURCE_KEYBOARD)
            .put("keyCode", KeyEvent.KEYCODE_A).put("metaState", 0).put("flags", flags).put("pairCount", 1)
            .put("physicalKeyboardVerified", false))
        recordProviderDecision("run_ime_key_event_acceptance")
        recordImeEvidence("before-single-ime-key-a")
        // This is the documented IME -> connected editor key-event API, not hardware injection.
        // The ordinary text path is covered by commitText above. No JS or direct View dispatch.
        compose.runOnIdle {
            val downTime = SystemClock.uptimeMillis()
            val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
            val up = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
            providerReport.getJSONObject("injection").put("downUnicodeChar", down.unicodeChar).put("upUnicodeChar", up.unicodeChar)
                .put("downTime", downTime).put("downAction", down.action).put("upAction", up.action)
            Log.i("TerminalImeKey", providerReport.getJSONObject("injection").toString())
            assertTrue(input.sendKeyEvent(down))
            assertTrue(input.sendKeyEvent(up))
        }
        verifySingleKeyAfterComposition("after-single-ime-key-a")
    }

    @Test fun systemKeyEventsReachTerminalAfterComposition() {
        setup()
        installImeEventProbe()
        val input = readyInputConnection("system-key")
        commitAndVerifyCjkComposition(input)
        providerReport.put("injection", JSONObject().put("api", "Instrumentation.sendKeyDownUpSync")
            .put("deviceId", KeyCharacterMap.VIRTUAL_KEYBOARD).put("source", InputDevice.SOURCE_KEYBOARD)
            .put("keyCode", KeyEvent.KEYCODE_A).put("metaState", 0).put("pairCount", 1)
            .put("physicalKeyboardVerified", false))
        recordProviderDecision("run_system_key_event_acceptance")
        recordImeEvidence("before-single-virtual-system-key-a")
        // AOSP InputDispatcher replaces normal injected device IDs with VIRTUAL_KEYBOARD_ID.
        // This verifies the synthetic system route, not a physical Bluetooth keyboard.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_A)
        verifySingleKeyAfterComposition("after-single-virtual-system-key-a")
    }

    @Test fun detachReattachKeepsTheSameTerminalAndNeverResendsInput() {
        requireHappyPathProvider()
        var visible by mutableStateOf(true)
        compose.runOnIdle {
            controller = TerminalController(relay, scope)
            renderer = requireNotNull(controller.renderer(compose.activity))
            controller.connect()
        }
        compose.setContent { if (visible) AndroidView(factory = renderer::createHost, onRelease = renderer::releaseHost, modifier = Modifier.fillMaxSize()) }
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
