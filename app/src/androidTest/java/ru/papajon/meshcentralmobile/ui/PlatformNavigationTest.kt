package ru.papajon.meshcentralmobile.ui

import android.app.Application
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.decodeHex
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity, platform back dispatch and local-only protocol fixture. No live server. */
class PlatformNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var workspace: ConnectionWorkspace
    private lateinit var settings: SettingsRepository
    private var initialProfileId: String? = null
    private var fixtureProfileId: String? = null
    private val savedPreferences = linkedMapOf<SharedPreferences, Map<String, *>>()

    @Before fun prepare() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/meshrelay.ashx") == true) {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("c")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            // Like MeshCentral, wait for the viewer protocol before streaming KVM.
                            if (text == "2") {
                                webSocket.send("0007000803200258".decodeHex()) // 800x600
                                webSocket.send("000b000e0003ffff00010002ffff".decodeHex()) // two monitors + all
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    })
                }
                if (request.path?.startsWith("/control.ashx") != true) {
                    return MockResponse().setHeader("Content-Type", "text/html")
                        .setBody("<html><body>Local navigation fixture</body></html>")
                }
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user//navigation"}}""")
                        webSocket.send("""{"action":"meshes","meshes":[{"_id":"mesh//navigation","mtype":2,"links":{"user//navigation":{"rights":4294967295}}}]}""")
                        webSocket.send("""{"action":"nodes","nodes":{"mesh//navigation":[{"_id":"node//navigation","name":"Navigation fixture","mtype":2,"agent":{"id":6,"caps":7},"conn":1,"pwr":1}]}}""")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (org.json.JSONObject(text).optString("action") == "authcookie") {
                            webSocket.send("""{"action":"authcookie","cookie":"fixture-cookie","rcookie":"fixture-relay-cookie"}""")
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                })
            }
        }
        server.start()
        // A fresh device shows the one-time immersive-mode hint as a focused system window, which
        // would take the next Back after entering fullscreen. Mark it as already acknowledged.
        shell("settings put secure immersive_mode_confirmations confirmed")
        // Creating the repository writes the default profile on a fresh device. Do it before the
        // snapshot, or the app's own later default write would look like an unrestored change.
        settings = SettingsRepository(context)
        for (name in listOf("app_settings", "app_update_preferences", "secure_vault", "mesh_recent_devices")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            savedPreferences[prefs] = prefs.all.toMap()
        }
        initialProfileId = settings.activeProfile.id
        context.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putLong("consent_version_code", BuildConfig.VERSION_CODE.toLong()).commit()
        val profile = settings.createProfile("Navigation fixture", server.url("/").toString(), onboardingCompleted = true)
        fixtureProfileId = profile.id
        settings.activateProfile(profile.id)
        settings.updateAllowHttpInDebug(true)
        settings.updateBlockScreenshots(false)
        instrumentation.runOnMainSync {
            workspace = ConnectionWorkspace.get(context.applicationContext as Application)
            check(!workspace.clearingSession) { "Previous session cleanup is still running" }
            workspace.closeAll()
            workspace.switchProfile(profile.id)
            assertEquals("Fixture profile was not activated", profile.id, workspace.profileId)
            workspace.client.login(server.url("/").toString(), "fixture", "synthetic-password")
        }
        compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("device-row-node//navigation").assertIsDisplayed()
    }

    @After fun cleanup() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
        attempt { scenario?.close() }
        attempt {
            if (::workspace.isInitialized && workspace.profileId == fixtureProfileId) {
                instrumentation.runOnMainSync {
                    workspace.closeAll()
                    workspace.client.logout(clearStoredCredentials = true)
                    SecureVault(context, requireNotNull(fixtureProfileId)).clearCredentials()
                }
            }
        }
        // Restore the original catalog before switching. Vault migration during the switch may
        // write preferences, so restore every saved value afterwards, even if a cleanup step failed.
        val catalog = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        attempt { savedPreferences[catalog]?.let { restorePreferences(catalog, it) } }
        attempt {
            if (::workspace.isInitialized && workspace.profileId == fixtureProfileId && initialProfileId != null) {
                instrumentation.runOnMainSync { workspace.switchProfile(requireNotNull(initialProfileId)) }
            }
        }
        savedPreferences.forEach { (prefs, values) -> attempt { restorePreferences(prefs, values) } }
        attempt { server.shutdown() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            throw first
        }
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit()) { "Could not restore test preferences" }
        assertTrue("Test preferences were not restored: " + (prefs.all.keys + values.keys).filter { prefs.all[it] != values[it] }.map { it + "=" + prefs.all[it] + " vs " + values[it] }, prefs.all == values)
    }

    @Test fun settingsSheetAndWebViewReturnToTheirParent() {
        compose.onNode(hasContentDescription(context.getString(R.string.common_nav_settings)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.onNodeWithText(context.getString(R.string.login_edit_server)).performScrollTo().performClick()
        compose.onNode(hasText(server.url("/").toString()) and hasSetTextAction()).assertIsDisplayed()
        snapshot("server-sheet")
        back()
        // Android 8 may focus the address and open the IME when the dialog appears.
        // The first Back then hides the IME; finish dismissing the dialog before clicking its parent.
        val addressField = hasText(server.url("/").toString()) and hasSetTextAction()
        if (compose.onAllNodes(addressField).fetchSemanticsNodes().isNotEmpty()) back()
        compose.onNode(addressField).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_debug_webview_action)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.common_action_close)).assertIsDisplayed()
        awaitLocalWebPage()
        snapshot("webview")
        back()
        compose.onNodeWithText(context.getString(R.string.settings_debug_webview_action)).performScrollTo().assertIsDisplayed()
        snapshot("settings-before-back")
        dismissImeIfVisible()
        back()
        snapshot("settings-after-back")
        compose.onNodeWithTag("device-row-node//navigation").performScrollTo().assertIsDisplayed()
    }

    @Test fun terminalFilesAndDeviceOverviewUsePlatformBackWithoutClosingTheActivity() {
        compose.onNodeWithTag("device-row-node//navigation").performClick()
        for (tab in listOf(DeviceTab.Terminal, DeviceTab.Files, DeviceTab.System)) {
            scenario!!.onActivity { ViewModelProvider(it)[MeshSessionViewModel::class.java].selectDeviceTab(tab) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(tab, workspace.deviceTab) }
            snapshot(tab.name)
            // Android 8 opens the IME for the focused terminal; Back closes it before navigating.
            dismissImeIfVisible()
            back()
            awaitAfterBack(5_000) { workspace.deviceTab == DeviceTab.Overview }
            compose.runOnIdle { assertEquals(DeviceTab.Overview, workspace.deviceTab) }
        }
        back()
        awaitAfterBack(5_000) { workspace.selectedDeviceId == null }
        compose.runOnIdle { assertNull(workspace.selectedDeviceId) }
        compose.onNodeWithTag("device-row-node//navigation").assertIsDisplayed()
    }

    @Test fun desktopBackMinimizesAndRetainsTheSession() {
        compose.onNodeWithContentDescription(context.getString(R.string.devices_action_connect)).performClick()
        compose.waitUntil(5_000) { workspace.sessions.any { it.tab == DeviceTab.Desktop } }
        snapshot("desktop")
        compose.onNodeWithText(context.getString(R.string.session_dock_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.session_options_fullscreen)).performScrollTo().performClick()
        assertTrue("The fullscreen option must activate fullscreen before Back is tested", fullscreenRequested())
        snapshot("desktop-options")
        back() // Dismiss the options sheet before leaving fullscreen or minimizing.
        compose.onNodeWithText(context.getString(R.string.session_dock_more)).assertIsDisplayed()
        snapshot("desktop-back-sheet")
        // Transient bars can be visible while fullscreen is still enabled. Observe the screen
        // mode without opening another dialog (which resets immersive gesture handling).
        // Immersive mode may reserve the first edge swipe for revealing its transient bars.
        repeat(2) { if (fullscreenRequested()) back() }
        assertFalse("Back must leave fullscreen", fullscreenRequested())
        compose.waitUntil(5_000) { statusBarVisible() }
        compose.onNodeWithText(context.getString(R.string.session_dock_more)).assertIsDisplayed()
        snapshot("desktop-back-fullscreen")
        back()
        awaitAfterBack(5_000) { workspace.selectedDeviceId == null }
        compose.onNodeWithTag("device-row-node//navigation").assertIsDisplayed()
        compose.runOnIdle { assertTrue(workspace.sessions.any { it.tab == DeviceTab.Desktop }) }
    }

    @Test fun tabletRotationRetainsTerminalAndSelectedDevice() {
        assumeTrue("Tablet acceptance requires sw600dp", context.resources.configuration.smallestScreenWidthDp >= 600)
        compose.onNodeWithTag("device-row-node//navigation").performClick()
        scenario!!.onActivity { ViewModelProvider(it)[MeshSessionViewModel::class.java].selectDeviceTab(DeviceTab.Terminal) }
        compose.waitForIdle()
        val terminal = workspace.selectedSession?.terminal
        assertNotNull(terminal)
        lateinit var retainedModel: MeshSessionViewModel
        scenario!!.onActivity {
            retainedModel = ViewModelProvider(it)[MeshSessionViewModel::class.java]
        }
        val previousAuto = shell("settings get system accelerometer_rotation").trim()
        val previousRotation = shell("settings get system user_rotation").trim()
        try {
            shell("settings put system accelerometer_rotation 0")
            for ((rotation, orientation) in listOf(1 to Configuration.ORIENTATION_LANDSCAPE, 0 to Configuration.ORIENTATION_PORTRAIT)) {
                shell("settings put system user_rotation $rotation")
                compose.waitUntil(10_000) { context.resources.configuration.orientation == orientation }
                compose.waitForIdle()
                scenario!!.onActivity {
                    assertSame(retainedModel, ViewModelProvider(it)[MeshSessionViewModel::class.java])
                }
                compose.onNodeWithText(context.getString(R.string.terminal_back)).assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals("node//navigation", workspace.selectedDeviceId)
                    assertSame(terminal, workspace.selectedSession?.terminal)
                }
                snapshot("tablet-$rotation")
            }
        } finally {
            shell("settings put system user_rotation $previousRotation")
            shell("settings put system accelerometer_rotation $previousAuto")
        }
    }

    private fun back() {
        compose.waitForIdle()
        if (usesGestureNavigation()) {
            // UiAutomation.windows is empty without FLAG_RETRIEVE_INTERACTIVE_WINDOWS.
            // The Activity already fills the display; use its current rotated pixel dimensions.
            val display = context.resources.displayMetrics
            shell("input swipe 1 ${display.heightPixels / 2} ${display.widthPixels / 2} ${display.heightPixels / 2} 350")
        } else instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // A second edge swipe during the system's finish animation is ignored. This animation
        // runs outside the Compose test clock and does not consistently emit accessibility events.
        SystemClock.sleep(1_000)
        // System predictive-back animation can finish after the injected swipe process exits.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    // Injected edge swipes are not recognised as Back by the API 35 (320x640) emulator image, even in
    // system Settings; there the Back key reaches the same OnBackInvokedCallback dispatch.
    private fun usesGestureNavigation() = Build.VERSION.SDK_INT >= 36 &&
        shell("settings get secure navigation_mode").trim() == "2"

    private fun fullscreenRequested(): Boolean {
        return compose.onAllNodesWithTag("remote-desktop-fullscreen").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun monitorMenuBackKeepsTheDesktopOpen() {
        openConnectedDesktop()
        compose.onNodeWithText(context.getString(R.string.session_screen_short, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.session_screen_short, 2)).assertIsDisplayed()
        back()
        compose.onNodeWithText(context.getString(R.string.session_screen_short, 2)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_dock_more)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("node//navigation", workspace.selectedDeviceId) }
    }

    @Test fun backDeactivatesRemoteKeyboardBeforeMinimizing() {
        openConnectedDesktop()
        val session = workspace.selectedSession
        compose.onNodeWithText(context.getString(R.string.session_dock_typing)).performClick()
        try {
            compose.waitUntil(15_000) { remoteFieldFocused() }
        } catch (failure: Throwable) {
            throw AssertionError("Remote field not focused; " + shell("dumpsys window").lines()
                .filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString(" | ") { it.trim() }, failure)
        }
        dismissImeIfVisible()
        // The IME's own Back may also deactivate capture on some platform versions.
        if (remoteFieldFocused()) back()
        compose.waitUntil(15_000) { !remoteFieldFocused() }
        // The IME's Back can hide the system keyboard and the field's focus while typing mode stays on;
        // then the app's own Back turns typing off first, still without leaving the desktop.
        val typing = hasClickAction() and hasText(context.getString(R.string.session_dock_typing))
        if (SemanticsProperties.Selected.let { key -> compose.onNode(typing).fetchSemanticsNode().config.getOrElse(key) { false } }) back()
        compose.onNode(typing).assertIsNotSelected()
        snapshot("keyboard-off")
        awaitImeHidden()
        compose.runOnIdle {
            assertEquals("node//navigation", workspace.selectedDeviceId)
            assertSame(session, workspace.selectedSession)
        }
        back()
        awaitAfterBack(15_000) { workspace.selectedDeviceId == null }
        compose.runOnIdle { assertTrue(workspace.sessions.contains(session)) }
    }

    /** Names the focused window when Back did not reach the app (a system window may have taken it). */
    private fun awaitAfterBack(timeoutMillis: Long, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (failure: Throwable) {
            throw AssertionError("Back did not reach the app; " + (shell("dumpsys window").lines()
                .filter { "mCurrentFocus" in it || "mFocusedApp" in it } + shell("dumpsys input_method").lines()
                .filter { "mInputShown" in it || "mImeWindowVis" in it || "mCurFocusedWindow=" in it })
                .joinToString(" | ") { it.trim() }, failure)
        }
    }

    private fun remoteFieldFocused(): Boolean {
        var focused = false
        scenario!!.onActivity {
            focused = it.currentFocus is ru.papajon.meshcentralmobile.ui.screens.session.RemoteInputField
        }
        return focused
    }

    private fun openConnectedDesktop() {
        compose.onNodeWithContentDescription(context.getString(R.string.devices_action_connect)).performClick()
        try {
            compose.waitUntil(10_000) {
                workspace.client.desktopState.value.let { it.inputEnabled && it.displays.size > 1 }
            }
        } catch (failure: Throwable) {
            val state = workspace.client.desktopState.value
            throw AssertionError("Local desktop fixture: state=${state.connectionState}, " +
                "handshake=${state.handshakeComplete}, displays=${state.displays}, " +
                "viewOnly=${state.viewOnly}, locked=${state.inputLocked}, message=${state.message}", failure)
        }
        compose.waitForIdle()
    }

    @Test fun cancelledEdgeGestureKeepsTheSelectedDevice() {
        assumeTrue("Cancelled edge gestures require gesture navigation", usesGestureNavigation())
        compose.onNodeWithTag("device-row-node//navigation").performClick()
        val display = context.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x,
                display.heightPixels / 2f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 1f)
        try {
            repeat(10) { step ->
                SystemClock.sleep(30)
                touch(MotionEvent.ACTION_MOVE, 1f + display.widthPixels * (step + 1) / 25f)
            }
        } finally { touch(MotionEvent.ACTION_CANCEL, display.widthPixels * 0.4f) }
        SystemClock.sleep(1_000)
        compose.runOnIdle { assertEquals("node//navigation", workspace.selectedDeviceId) }
        // A completed gesture at the same destination must still perform Back.
        back()
        awaitAfterBack(5_000) { workspace.selectedDeviceId == null }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun dismissImeIfVisible() {
        // On Android 8, restoring Settings can focus its PIN field and show the numeric IME.
        // Back must dismiss this actual system window before it changes the app destination.
        withImeWindows { imeVisible ->
            if (imeVisible()) {
                back()
                compose.waitUntil(5_000) { !imeVisible() }
            }
        }
    }

    /** While the IME is still hiding, Android 13+ keeps its Back callback ahead of the app's. */
    private fun awaitImeHidden() = withImeWindows { imeVisible -> compose.waitUntil(5_000) { !imeVisible() } }

    private fun withImeWindows(action: (imeVisible: () -> Boolean) -> Unit) {
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            automation.waitForIdle(100, 5_000)
            action { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    @Suppress("DEPRECATION")
    private fun statusBarVisible(): Boolean {
        var visible = false
        scenario!!.onActivity {
            val decor = it.window.decorView
            visible = if (Build.VERSION.SDK_INT < 30) {
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            } else ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(WindowInsetsCompat.Type.statusBars()) == true
        }
        return visible
    }

    private fun awaitLocalWebPage() {
        fun findWebView(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        var webView: WebView? = null
        scenario!!.onActivity { webView = findWebView(it.window.decorView) }
        val browser = requireNotNull(webView) { "Debug WebView was not attached" }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val evaluated = CountDownLatch(1)
            var body = ""
            instrumentation.runOnMainSync {
                browser.evaluateJavascript("document.body ? document.body.innerText : ''") {
                    body = it; evaluated.countDown()
                }
            }
            assertTrue("WebView did not answer", evaluated.await(3, TimeUnit.SECONDS))
            if (body.contains("Local navigation fixture")) return
            SystemClock.sleep(100)
        }
        fail("WebView did not load the local fixture page")
    }

    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("platformScreenshots") != "true") return
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        scenario!!.onActivity {
            val view = it.window.decorView
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                view.invalidate()
            } else view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
        }
        assertTrue("Window did not draw before capture", frame.await(5, TimeUnit.SECONDS))
        val directory = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        require(directory.matches(Regex("/[A-Za-z0-9_./-]+")))
        val path = "$directory/api36-$name.png"
        // UTP creates this directory as shell, so capture as shell rather than the app UID.
        // executeShellCommand tokenizes arguments; it does not interpret shell quotes/operators.
        shell("mkdir -p $directory")
        shell("rm -f $path")
        shell("screencap -p $path")
        assertTrue("Screenshot was not saved", shell("ls -l $path").contains("api36-$name.png"))
    }
}
