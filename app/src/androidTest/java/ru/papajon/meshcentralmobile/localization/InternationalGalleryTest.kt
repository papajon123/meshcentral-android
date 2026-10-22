package ru.papajon.meshcentralmobile.localization

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.terminal.TerminalController
import ru.papajon.meshcentralmobile.terminal.TerminalWebRenderer
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.screens.DeviceScreen
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.screens.FilesScreen
import ru.papajon.meshcentralmobile.ui.screens.TerminalScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Opt-in documentation gallery using the production Composable screens.
 * Opt-in: -e publicGallery true. Nine real UI captures, with entirely synthetic session data.
 * Setup is intentionally excluded: its public image must come from the real release APK.
 * No MeshCentralClient, HTTP client, real server/account, disk listing, or SAF operation is used.
 * Screenshots are unmodified UiAutomation captures in targetContext.cacheDir.
 */
@OptIn(ExperimentalTestApi::class)
class InternationalGalleryTest {
    // An empty rule does not launch an Activity before @Before has checked the opt-in.
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private lateinit var activity: ComponentActivity
    private val frame = mutableStateOf(GalleryFrame("en", Screen.Blank))
    private var ownerScope: CoroutineScope? = null
    private var terminal: TerminalController? = null
    private var files: FilesController? = null
    private var relay: GalleryRelay? = null
    private var screenshotCount = 0

    private enum class Screen(val filePart: String) { Blank("blank"), Devices("devices"), Terminal("terminal"), Files("files") }
    private data class GalleryFrame(val language: String, val screen: Screen)

    @Before fun requireExplicitGalleryOptIn() {
        assumeTrue("Public gallery is opt-in only", InstrumentationRegistry.getArguments().getString("publicGallery") == "true")
        assumeTrue("Gallery requires a dedicated modern Android emulator", Build.VERSION.SDK_INT >= 35 && Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @After fun closeGallery() {
        if (scenario != null) {
            try { disposeSession() } finally { scenario?.close(); scenario = null }
        }
    }

    @Test fun nineInternationalScreensUseRenderedPixelsAndSyntheticData() {
        for (language in listOf("en", "zh-Hans", "ru")) {
            for (screen in listOf(Screen.Devices, Screen.Terminal, Screen.Files)) {
                File(app.cacheDir, "gallery-$language-${screen.filePart}.png").delete()
            }
        }
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity {
                activity = it
                it.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.window.isNavigationBarContrastEnforced = false
                it.setContent { GalleryContent() }
            }
        }
        for (language in listOf("en", "zh-Hans", "ru")) {
            val localized = contextFor(language)
            show(language, Screen.Devices)
            checkText(localized.getString(R.string.devices_title))
            checkText("Lab")
            checkText("Build server")
            checkText("Workstation")
            capture(language, Screen.Devices, localized.getString(R.string.devices_title))

            createTerminal(language)
            show(language, Screen.Terminal)
            compose.waitUntil(15_000) { terminal?.state?.value?.ready == true }
            lateinit var renderer: TerminalWebRenderer
            compose.runOnIdle {
                renderer = requireNotNull(requireNotNull(terminal).renderer(localized)) { "Gallery WebView initialization failed" }
            }
            compose.waitUntil(15_000) { evaluateBoolean(renderer, "typeof window.meshTerminal === 'object'") }
            val visibleOutput = "(() => {window.meshTerminal.selectAll();const s=window.meshTerminal.selection();const ok=s.includes(${JSONObject.quote(terminalResult(language))}) && s.includes('24') && s.split('lab@build-server:~$').length===3;window.meshTerminal.clearSelection();return ok;})()"
            compose.waitUntil(15_000) { evaluateBoolean(renderer, visibleOutput) }
            checkText("Build server")
            checkText(localized.getString(R.string.terminal_connected))
            checkText(localized.getString(R.string.terminal_back))
            checkText(localized.getString(R.string.terminal_keyboard), scroll = true)
            checkText(localized.getString(R.string.terminal_ctrl), scroll = true)
            checkText(localized.getString(R.string.terminal_esc), scroll = true)
            checkText(localized.getString(R.string.terminal_keyboard), scroll = true)
            waitForWebViewFrame(renderer)
            capture(language, Screen.Terminal, "Build server", renderer)
            disposeSession()

            createFiles(language)
            show(language, Screen.Files)
            compose.waitUntil(10_000) { files?.state?.value?.let { it.connected && !it.busy && it.entries.size == 4 } == true }
            checkText("Build server")
            checkText(localized.getString(R.string.files_title))
            checkText(localized.getString(R.string.files_refresh))
            checkText(localized.getString(R.string.files_mkdir))
            checkText(localized.getString(R.string.files_upload))
            checkText(sampleNoteName(language), scroll = true)
            capture(language, Screen.Files, "Build server")
            disposeSession()
        }
        assertTrue("Gallery must contain exactly three screens in each of three languages", screenshotCount == 9)
    }

    @Composable private fun GalleryContent() {
        val current = frame.value
        val localized = contextFor(current.language)
        val configuration = localized.resources.configuration
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides configuration,
            // The localized application Context cannot discover these Activity owners itself.
            LocalActivityResultRegistryOwner provides activity,
            LocalOnBackPressedDispatcherOwner provides activity,
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            MeshCentralMobileTheme(darkTheme = InstrumentationRegistry.getArguments().getString("galleryTheme") != "light") {
                Surface(modifier = Modifier.fillMaxSize()) {
                    key(current.language, current.screen) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            when (current.screen) {
                                Screen.Blank -> Unit
                                // This is the real production content used by DevicesScreen, without a network client.
                                Screen.Devices -> DevicesContent(
                                    clientState = syntheticState(), favorites = emptyList(),
                                    onAddFavorite = { _, _ -> }, onRemoveFavorite = {}, onConnect = {},
                                    onRefresh = {}, onOpenSettings = {}, onSignIn = {}, onOpenDevice = {},
                                )
                                Screen.Terminal, Screen.Files -> DeviceScreen(
                                    device = buildServer(), server = MeshServerCapabilities(features2 = 512), current = true,
                                    selected = if (current.screen == Screen.Terminal) DeviceTab.Terminal else DeviceTab.Files,
                                    onSelect = {}, onBack = {}, onWebPanel = {},
                                ) {
                                    if (current.screen == Screen.Terminal) TerminalScreen(requireNotNull(terminal), onBack = {})
                                    else FilesScreen(requireNotNull(files), onBack = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun show(language: String, screen: Screen) {
        compose.runOnIdle { frame.value = GalleryFrame(language, screen) }
        compose.waitForIdle()
    }

    private fun createTerminal(language: String) {
        compose.runOnIdle {
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            relay = GalleryRelay(RelayProtocol.AgentTerminal, language)
            terminal = TerminalController(requireNotNull(relay), requireNotNull(ownerScope))
        }
    }

    private fun createFiles(language: String) {
        compose.runOnIdle {
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            relay = GalleryRelay(RelayProtocol.AgentFiles, language)
            files = FilesController(requireNotNull(relay), requireNotNull(ownerScope), contextFor(language))
        }
    }

    private fun disposeSession() {
        if (scenario == null) return
        compose.runOnIdle { frame.value = frame.value.copy(screen = Screen.Blank) }
        compose.waitForIdle() // Unmount the old AndroidView before destroying its renderer.
        compose.runOnIdle { terminal?.close(); files?.close(); relay?.close() }
        ownerScope?.coroutineContext?.get(Job)?.let { job -> runBlocking { withTimeout(5_000) { job.cancelAndJoin() } } }
        terminal = null; files = null; relay = null; ownerScope = null
        compose.waitForIdle()
    }

    private fun checkText(text: String, scroll: Boolean = false) {
        val matches = compose.onAllNodesWithText(text, useUnmergedTree = true)
        if (scroll && matches.fetchSemanticsNodes().isEmpty()) {
            val lists = compose.onAllNodes(hasScrollToIndexAction())
            assertTrue("Expected scrollable gallery list is missing", lists.fetchSemanticsNodes().isNotEmpty())
            lists[0].performScrollToNode(hasText(text))
            compose.waitForIdle()
        }
        val refreshed = compose.onAllNodesWithText(text, useUnmergedTree = true)
        assertTrue("Expected gallery label is missing: $text (${frame.value.language}/${frame.value.screen.filePart})", refreshed.fetchSemanticsNodes().isNotEmpty())
        val node = refreshed[0]
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Gallery label has no measured glyph layout", layouts.isNotEmpty())
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertFalse("Gallery label is clipped or ellipsized: ${frame.value.language}/${frame.value.screen.filePart}", layouts.any { layout ->
            (0 until layout.lineCount).any { line ->
                layout.isLineEllipsized(line) ||
                    layout.getLineRight(line) - layout.getLineLeft(line) > bounds.width + 2f ||
                    layout.getLineBottom(line) > bounds.height + 2f
            }
        })
    }

    private fun contextFor(language: String): Context {
        val locales = LocaleList.forLanguageTags(language)
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        })
    }

    private fun evaluateBoolean(renderer: TerminalWebRenderer, script: String): Boolean {
        val done = CountDownLatch(1)
        var result = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderer.view.evaluateJavascript(script) { result = it == "true"; done.countDown() }
        }
        assertTrue("Gallery WebView callback did not complete", done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun waitForWebViewFrame(renderer: TerminalWebRenderer) {
        val ready = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderer.view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    renderer.view.postOnAnimation { renderer.view.postOnAnimation { ready.countDown() } }
                }
            })
        }
        assertTrue("Gallery WebView did not commit its visual frame", ready.await(10, TimeUnit.SECONDS))
    }

    private fun capture(language: String, screen: Screen, headerText: String, renderer: TerminalWebRenderer? = null) {
        compose.waitForIdle()
        val output = File(app.cacheDir, "gallery-$language-${screen.filePart}.png")
        val startedAt = SystemClock.uptimeMillis()
        var attempts = 0
        while (true) {
            // A ready semantic tree may precede the first composited application window.
            // Wait for evidence in actual pixels, bounded to five seconds, never an arbitrary sleep.
            val frameReady = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.window.decorView.postOnAnimation { frameReady.countDown() }
            }
            assertTrue("Gallery window did not produce a frame", frameReady.await(5, TimeUnit.SECONDS))
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
            val header = compose.onAllNodesWithText(headerText, useUnmergedTree = true)[0].assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            val origin = IntArray(2)
            var focused = false
            val viewport = Rect()
            compose.runOnIdle {
                activity.window.decorView.getLocationOnScreen(origin)
                focused = activity.hasWindowFocus()
                if (renderer != null) assertTrue("Gallery terminal is detached or hidden", renderer.view.isAttachedToWindow && renderer.view.isShown && renderer.view.getGlobalVisibleRect(viewport))
            }
            val headerRect = Rect(header.left.toInt() + origin[0], header.top.toInt() + origin[1], header.right.toInt() + origin[0], header.bottom.toInt() + origin[1])
            val headerPixels = variedPixels(readable, headerRect)
            val terminalPixels = if (renderer == null) 128 else variedPixels(readable, viewport)
            attempts++
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val ready = focused && headerPixels >= 32 && terminalPixels >= 128
            val timedOut = elapsed >= 5_000
            if (ready || timedOut) {
                // Keep the true frame, including a failing frame for diagnosis. No edits or crop.
                output.outputStream().use { assertTrue("Could not save gallery PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                File(app.cacheDir, "gallery-$language-${screen.filePart}-evidence.json").writeText(JSONObject()
                    .put("attempts", attempts).put("elapsedMillis", elapsed).put("windowFocused", focused)
                    .put("headerRect", headerRect.toShortString()).put("headerPixels", headerPixels)
                    .put("terminalPixels", terminalPixels).put("ready", ready).toString())
            }
            if (readable !== bitmap) readable.recycle()
            bitmap.recycle()
            if (ready || timedOut) {
                assertTrue("Gallery Activity does not own the visible window", focused)
                assertTrue("Gallery screenshot contains a blank header after waiting for actual pixels", headerPixels >= 32)
                assertTrue("Gallery screenshot contains a blank terminal despite DOM readiness", terminalPixels >= 128)
                screenshotCount++
                return
            }
        }
    }

    private fun variedPixels(bitmap: Bitmap, requested: Rect): Int {
        val bounds = Rect(requested)
        assertTrue("Gallery screenshot region is outside the display", bounds.intersect(0, 0, bitmap.width, bitmap.height))
        val counts = HashMap<Int, Int>()
        var count = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val color = bitmap.getPixel(x, y) and 0x00F8F8F8
            counts[color] = (counts[color] ?: 0) + 1
            count++
        }
        return count - (counts.values.maxOrNull() ?: 0)
    }

    /** In-memory relay only: no sockets, no credentials, no filesystem operations. */
    private class GalleryRelay(override val protocol: RelayProtocol, private val language: String) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus())
        private val frames = Channel<RelayFrame>(8)
        override val incoming = frames.receiveAsFlow()
        private val started = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        override fun connect() {
            if (!started.compareAndSet(false, true) || closed.get()) return
            state.value = RelayStatus(RelayPhase.Connected)
            if (protocol == RelayProtocol.AgentTerminal) {
                val result = terminalResult(language)
                val output = "\u001b[36mLab — Build server\u001b[0m\r\n\r\nlab@build-server:~$ check-build\r\n\u001b[32m[OK]\u001b[0m $result\r\n\u001b[32m[OK]\u001b[0m 24 tests\r\n\r\nlab@build-server:~$ "
                check(frames.trySend(RelayFrame.Binary(output.toByteArray(Charsets.UTF_8))).isSuccess)
            }
        }
        override fun sendText(value: String): Boolean {
            if (closed.get() || state.value.phase != RelayPhase.Connected) return false
            val message = runCatching { JSONObject(value) }.getOrNull() ?: return false
            if (protocol == RelayProtocol.AgentTerminal) return message.optString("ctrlChannel") == "102938"
            if (message.optString("action") != "ls") return false // Mutations/transfers are deliberately absent.
            val entries = JSONArray()
                .put(JSONObject().put("n", "Documents").put("t", 2))
                .put(JSONObject().put("n", "Build output").put("t", 2))
                .put(JSONObject().put("n", sampleNoteName(language)).put("t", 3).put("s", 2048))
                .put(JSONObject().put("n", "Release notes.md").put("t", 3).put("s", 8192))
            val response = JSONObject().put("path", "Lab").put("reqid", message.getInt("reqid")).put("dir", entries)
            return frames.trySend(RelayFrame.Binary(response.toString().toByteArray(Charsets.UTF_8))).isSuccess
        }
        override fun sendBinary(value: ByteArray): Boolean = !closed.get() && state.value.phase == RelayPhase.Connected && protocol == RelayProtocol.AgentTerminal
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            state.value = RelayStatus(RelayPhase.Closed)
            frames.cancel()
        }
    }

    companion object {
        private fun buildServer() = MeshDevice("node//gallery-build", "mesh//gallery-lab", "Build server", meshName = "Lab",
            conn = 1, pwr = 1, osDescription = "Debian GNU/Linux", mtype = 2, agentId = 6, agentCaps = 7, rights = MeshRights.FULL)
        private fun syntheticState() = MeshClientState(connectionState = ConnectionState.Authenticated,
            serverName = "meshcentral.example.com", groups = listOf(MeshGroup("mesh//gallery-lab", "Lab")),
            devices = listOf(buildServer(), MeshDevice("node//gallery-workstation", "mesh//gallery-lab", "Workstation", meshName = "Lab",
                conn = 0, osDescription = "Windows 11", mtype = 2, agentId = 4, agentCaps = 7, rights = MeshRights.FULL)),
            socketConnected = true, requiresSignIn = false, devicesCurrent = true)
        private fun sampleNoteName(language: String): String = when (language) { "ru" -> "Заметки.txt"; "zh-Hans" -> "说明.txt"; else -> "Notes.txt" }
        private fun terminalResult(language: String): String = when (language) { "ru" -> "Проверки пройдены"; "zh-Hans" -> "所有检查已通过"; else -> "All checks passed" }
    }
}
