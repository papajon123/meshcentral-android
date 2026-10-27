package ru.papajon.meshcentralmobile.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.chat.*
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real controller and local WebSocket; production Composables, synthetic text only. */
class ChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient()
    private val web = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()
    private val invites = AtomicInteger()
    private val backs = AtomicInteger()
    @Volatile private var peer: WebSocket? = null
    private var controller: ChatController? = null
    private var originalHardwareIme: Int? = null

    @Before fun enableSoftwareKeyboardForUiChecks() {
        compose.activityRule.scenario.onActivity(::configureWindow)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        originalHardwareIme = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver, "show_ime_with_hard_keyboard")?.toIntOrNull()
        instrumentation.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }
    private fun configureWindow(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        @Suppress("DEPRECATION")
        activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (android.os.Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
        androidx.core.view.ViewCompat.requestApplyInsets(activity.window.decorView)
    }
    private fun restoreContent(chat: ChatController, rtl: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            configureWindow(activity)
            activity.setContent { Content(chat, rtl) }
        }
    }

    private fun keyboard(visible: Boolean) {
        if (visible) compose.onNodeWithTag("chat-input").performClick()
        compose.activityRule.scenario.onActivity { activity ->
            val insets = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (visible) insets.show(androidx.core.view.WindowInsetsCompat.Type.ime())
            else insets.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            (androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) == visible
        }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        try {
            compose.runOnUiThread { controller?.close(); scope.cancel() }
            peer?.close(1000, null); web.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        } finally {
            val command = originalHardwareIme?.let { "settings put secure show_ime_with_hard_keyboard $it" }
                ?: "settings delete secure show_ime_with_hard_keyboard"
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
            }
        }
    }
    private fun enqueue(closeUnpaired: Boolean = false, closeDelay: Long = 3_200) {
        web.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer = webSocket
                if (closeUnpaired) scope.launch { delay(closeDelay); webSocket.close(1000, null) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { received += text }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
    }
    private fun start(rtl: Boolean = false): ChatController {
        web.start(); enqueue()
        val relayRequest = Request.Builder().url(web.url("/meshrelay.ashx?id=fixture")).build()
        val chat = ChatController(scope, http, { relayRequest },
            { true }, { invites.incrementAndGet(); true })
        controller = chat
        show(chat, rtl)
        compose.waitUntil(10_000) { chat.state.value.phase == ChatPhase.Waiting && peer != null }
        return chat
    }
    @Composable private fun Content(chat: ChatController, rtl: Boolean) {
        val registryOwner = requireNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
        val activity = LocalContext.current
        val configuration = LocalConfiguration.current
        val context = remember(activity, configuration, rtl) { activity.createConfigurationContext(Configuration(configuration).apply {
            setLocale(Locale.forLanguageTag(if (rtl) "ar" else "en"))
        }) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner,
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            MeshCentralMobileTheme {
                Surface(Modifier.safeDrawingPadding()) {
                    Column {
                        Text("Fixture profile", Modifier.height(48.dp))
                        DeviceScreen(ru.papajon.meshcentralmobile.mesh.MeshDevice("node//fixture", "mesh//fixture", "Fixture PC"),
                            ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities(), true, ru.papajon.meshcentralmobile.ui.DeviceTab.Chat,
                            {}, {}, {}) { ChatScreen(chat, onClose = chat::close, deviceName = "Fixture PC", onBack = { backs.incrementAndGet() }) }
                    }
                }
            }
        }
    }
    private fun show(chat: ChatController, rtl: Boolean) { compose.setContent { Content(chat, rtl) } }
    private fun connected(chat: ChatController) {
        peer!!.send("c")
        peer!!.send("""{"action":"random","random":0.5}""")
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Connected }
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        // Compose can be idle while Android is still animating an orientation change.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3_000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "chat-shots/$name.png")
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun recoveryKeepsTranscriptScrollPosition() {
        val chat = start(); connected(chat)
        repeat(45) { peer!!.send(ChatProtocol.message("Scroll marker $it")) }
        compose.waitUntil(5_000) { chat.state.value.messages.size == 45 }
        compose.onNodeWithTag("chat-messages").performScrollToIndex(11)
        compose.onNodeWithText("Scroll marker 10").assertIsDisplayed()
        val before = compose.onNodeWithText("Scroll marker 10").fetchSemanticsNode().boundsInRoot
        enqueue()
        compose.runOnUiThread { chat.onNetworkLost() }
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.Waiting }
        compose.onNodeWithText("Scroll marker 10").assertIsDisplayed()
        compose.onNodeWithTag("chat-status").assertIsDisplayed()
        compose.onNodeWithTag("chat-request").assertIsDisplayed()
        connected(chat)
        assertEquals(before, compose.onNodeWithText("Scroll marker 10").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun recoveryStatusAndRetryStayAboveLongScrolledTranscript() {
        var state by mutableStateOf(ChatState(phase = ChatPhase.Connected,
            messages = (1..80).map { ChatMessage(it.toLong(), "Long transcript row $it", false, 1234) }))
        var requests = 0
        compose.setContent {
            MeshCentralMobileTheme { Surface(Modifier.safeDrawingPadding()) {
                ChatContent(state, {}, {}, { requests++ }, {})
            } }
        }
        compose.onNodeWithTag("chat-messages").performScrollToIndex(80)
        for (phase in listOf(ChatPhase.ServerRetained, ChatPhase.PeerLeft, ChatPhase.Failed, ChatPhase.Waiting, ChatPhase.Verifying)) {
            compose.runOnIdle { state = state.copy(phase = phase) }
            compose.onNodeWithTag("chat-messages").performScrollToIndex(80)
            compose.onNodeWithTag("chat-status").assertIsDisplayed()
            compose.onNodeWithTag("chat-request").assertIsDisplayed()
            val status = compose.onNodeWithTag("chat-status").fetchSemanticsNode().boundsInRoot
            val transcript = compose.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot
            assertTrue("Status must be outside the scrollable transcript", status.bottom <= transcript.top)
            if (phase in listOf(ChatPhase.ServerRetained, ChatPhase.Verifying)) compose.onNodeWithTag("chat-request").assertIsNotEnabled()
            else compose.onNodeWithTag("chat-request").assertIsEnabled().performClick()
        }
        assertEquals(3, requests)
        snapshot("pinned-long-transcript")
    }
    @Test fun retainedPairControlsRemainVisibleWithLandscapeKeyboard() {
        val chat = start(); connected(chat)
        // Rotate before opening IME: Gboard's configuration reset can otherwise hide a
        // newly requested keyboard after the new Activity has already received focus.
        compose.runOnIdle { chat.setDraft("Retained draft") }
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        restoreContent(chat)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3_000)
        compose.waitUntil(10_000) {
            val view = compose.activity.window.decorView
            view.hasWindowFocus() && view.width > view.height
        }
        compose.onNodeWithTag("chat-input").assertTextContains("Retained draft").performClick().assertIsFocused()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        var lastImeRequest = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val visible = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            val now = android.os.SystemClock.uptimeMillis()
            // Gboard may cancel the first request while rebuilding for landscape. Establish
            // the visible-IME precondition before checking the production layout below.
            if (!visible && compose.activity.hasWindowFocus() && now - lastImeRequest >= 500) {
                compose.runOnUiThread {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                // Android 16 cancels duplicate show requests when the previous request never
                // obtained IME controls. Let hide reset that request before asking again.
                android.os.SystemClock.sleep(250)
                compose.runOnUiThread {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                lastImeRequest = now
            }
            visible
        }
        // A duplicate socket closes immediately, before c.
        enqueue(closeUnpaired = true, closeDelay = 10)
        compose.runOnUiThread { chat.onNetworkLost() }
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.ServerRetained }
        var previousBounds: androidx.compose.ui.geometry.Rect? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val bounds = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInWindow
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previousBounds) stableSince = now
            previousBounds = bounds
            now - stableSince >= 500
        }
        snapshot("retained-landscape-keyboard")
        assertTrue("Keyboard must remain visible during recovery checks",
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
        compose.onNodeWithTag("chat-status", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat-request").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
        compose.onNode(hasContentDescription("Close chat") or hasText("Close chat")).assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp)
        snapshot("retained-landscape-keyboard")
    }
    @Test fun sendReceiveAndPeerCloseUseTheRealController() {
        val chat = start()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for the user to open the chat window").assertIsDisplayed()
        snapshot("waiting")
        connected(chat)
        compose.onNodeWithTag("chat-input").performTextInput("Hello from Android")
        compose.onNodeWithTag("chat-input").performImeAction()
        compose.waitUntil(5_000) { received.any { JSONObject(it).optString("msg") == "Hello from Android" } }
        compose.onNodeWithText("Hello from Android").assertIsDisplayed()
        peer!!.send(ChatProtocol.message("<b>Plain text</b> https://example.invalid"))
        compose.waitUntil(5_000) { chat.state.value.messages.size == 2 }
        compose.onNodeWithText("<b>Plain text</b> https://example.invalid").assertIsDisplayed()
        snapshot("connected")
        enqueue(closeUnpaired = true)
        peer!!.close(1000, null)
        compose.waitUntil(5_000) { chat.state.value.phase == ChatPhase.PeerLeft }
        // Explicitly exercise the regular layout, independently of the AVD's natural orientation.
        keyboard(false)
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-request").assertIsDisplayed()
        compose.onNodeWithText("The user left the chat or the connection ended").assertIsDisplayed()
        snapshot("peer-left")
    }
    @Test fun peerClosureUsesRetryInsteadOfSendWithLandscapeKeyboard() {
        val chat = start(); connected(chat)
        compose.onNodeWithTag("chat-input").performTextInput("Draft survives closure")
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        restoreContent(chat)
        keyboard(true)
        enqueue(closeUnpaired = true)
        peer!!.close(1000, null)
        compose.waitUntil(10_000) { chat.state.value.phase == ChatPhase.PeerLeft && chat.state.value.canRequest }
        compose.waitForIdle()
        val imeBottom = compose.runOnUiThread {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        if (imeBottom > 0) {
            // A docked landscape keyboard: compact recovery replaces Send with Retry next to the input.
            compose.onNodeWithTag("chat-send").assertDoesNotExist()
        } else {
            // Phones show the landscape keyboard full screen (extract mode) without an IME inset for the
            // window, so the regular recovery controls stay and Send is disabled.
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        }
        compose.onNodeWithTag("chat-request").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("chat-input").assertTextContains("Draft survives closure")
        snapshot("peer-left-compact")
    }
    @Test fun retryRequestsAnotherWindowAndCloseClearsConversation() {
        val chat = start(); connected(chat)
        peer!!.send(ChatProtocol.message("First session"))
        compose.waitUntil { chat.state.value.messages.isNotEmpty() }
        enqueue(closeUnpaired = true)
        peer!!.close(1000, null)
        compose.waitUntil(8_000) { chat.state.value.phase == ChatPhase.PeerLeft && chat.state.value.canRequest }
        enqueue(); peer = null
        compose.onNodeWithTag("chat-request").performClick()
        compose.waitUntil(5_000) { peer != null && chat.state.value.phase == ChatPhase.Waiting }
        compose.waitUntil(5_000) { invites.get() == 2 }
        connected(chat)
        assertEquals(2, invites.get())
        compose.onNodeWithText("First session").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs.get()); assertEquals(ChatPhase.Connected, chat.state.value.phase) }
        compose.onNodeWithText("Close chat").performClick()
        compose.onNodeWithText("Close this chat and erase its messages?").assertIsDisplayed()
        compose.onNodeWithTag("chat-confirm-close").performClick()
        compose.runOnIdle { assertEquals(ChatPhase.Closed, chat.state.value.phase); assertTrue(chat.state.value.messages.isEmpty()) }
    }
    @Test fun longTextIsBoundedAndArabicLayoutKeepsSendReachable() {
        val chat = start(rtl = true); connected(chat)
        compose.onNodeWithTag("chat-input").performTextInput("س".repeat(4100))
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(4097, chat.state.value.draft.length) }
        compose.onNodeWithTag("chat-input").performTextReplacement("مرحبا من الهاتف")
        compose.onNodeWithTag("chat-send").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { received.isNotEmpty() }
        assertEquals("مرحبا من الهاتف", JSONObject(received.last()).getString("msg"))
        peer!!.send(ChatProtocol.message("نص طويل ".repeat(300)))
        compose.waitUntil(15_000) { chat.state.value.messages.size == 2 }
        compose.onNodeWithTag("chat-send").assertIsDisplayed()
        snapshot("rtl-long")
    }
    @Test fun oversizedIncomingMessageIsVisibleWithTruncationMarker() {
        val chat = start(); connected(chat)
        peer!!.send(JSONObject().put("action", "chat").put("msg", "x".repeat(5000)).toString())
        compose.waitUntil(5_000) { chat.state.value.messages.size == 1 }
        compose.onNodeWithText("Message shortened").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(chat.state.value.messages.single().truncated)
            assertEquals(4096, chat.state.value.messages.single().text.length)
        }
        snapshot("truncated")
    }
    @Test fun recreationAndRotationPreserveDraftHistoryAndSingleInvite() {
        val chat = start(); connected(chat)
        peer!!.send(ChatProtocol.message("Before rotation"))
        compose.waitUntil { chat.state.value.messages.isNotEmpty() }
        compose.onNodeWithTag("chat-input").performTextInput("Unsent draft")
        compose.activityRule.scenario.recreate()
        restoreContent(chat)
        compose.onNodeWithText("Before rotation").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertTextContains("Unsent draft")
        compose.runOnUiThread { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        restoreContent(chat)
        compose.onNodeWithTag("chat-input").assertTextContains("Unsent draft")
        keyboard(true)
        compose.onNodeWithTag("chat-send").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("chat-input").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
        compose.onNode(hasContentDescription("Close chat") or hasText("Close chat")).assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("chat-send").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { received.any { JSONObject(it).optString("msg") == "Unsent draft" } }
        assertEquals(1, received.count { JSONObject(it).optString("msg") == "Unsent draft" })
        assertEquals(1, invites.get())
        snapshot("landscape")
    }
}
