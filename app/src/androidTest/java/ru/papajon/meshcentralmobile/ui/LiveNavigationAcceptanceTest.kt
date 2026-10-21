package ru.papajon.meshcentralmobile.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.terminal.TerminalWebRenderer

/** Real activity and real isolated server. No Activity or network before explicit fixture opt-in. */
@OptIn(ExperimentalTestApi::class)
class LiveNavigationAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private var previousSettings: Map<String, *>? = null
    private var previousSecure: Map<String, *>? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var model: MeshSessionViewModel
    private lateinit var agentNodeId: String
    private lateinit var agentName: String
    private lateinit var renderer: TerminalWebRenderer
    private var systemPickerOpen = false
    private var stage = "fixture bootstrap"

    @Before fun prepareBeforeActivity() {
        assumeTrue("Live fixture was not explicitly selected", InstrumentationRegistry.getArguments().getString("liveFixture") == FIXTURE_URL)
        assumeTrue("Live fixture is debug-only", BuildConfig.DEBUG)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        safe {
            val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
            val fixture = try {
                http.newCall(Request.Builder().url(FIXTURE_URL).build()).execute().use { response ->
                    assertTrue("Isolated fixture is not ready", response.code == 200)
                    val body = requireNotNull(response.body)
                    assertTrue("Unexpected fixture response length", body.contentLength() in 1..16_384)
                    JSONObject(body.string())
                }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            assertTrue("Bootstrap selected a non-fixture server", fixture.getString("serverUrl") == FIXTURE_SERVER)
            assertTrue("Bootstrap selected a non-fixture directory", fixture.getString("testDirectory") == FIXTURE_DIRECTORY)
            agentNodeId = fixture.getString("agentNodeId")
            assertTrue("Unexpected fixture node identifier", agentNodeId.startsWith("node//"))

            // Preserve existing settings and encrypted vault values in memory, never in an artifact.
            settingsPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            securePrefs = context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE)
            previousSettings = settingsPrefs.all.toMap()
            previousSecure = securePrefs.all.toMap()
            val settings = SettingsRepository(context)
            val vault = SecureVault(context)
            vault.clearPin()
            vault.clearCredentials()
            settings.updateServerUrl(FIXTURE_SERVER)
            settings.updateTrustSelfSignedCertificates(true)
            settings.updateBlockScreenshots(false)
            settings.updateBiometricUnlock(false)
            settings.completeOnboarding()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val setupClient = MeshCentralClient(vault, scope)
            try {
                stage = "authenticate before launching MainActivity"
                setupClient.setAllowSelfSignedCertificates(true)
                setupClient.login(FIXTURE_SERVER, fixture.getString("username"), fixture.getString("password"))
                runBlocking {
                    withTimeout(45_000) {
                        val ready = setupClient.clientState.first { it.socketConnected && !it.requiresSignIn && it.devicesCurrent && it.devices.any { device -> device.nodeId == agentNodeId } }
                        agentName = ready.devices.single { it.nodeId == agentNodeId }.name
                    }
                }
                assertTrue("Successful fixture login did not persist through SecureVault", vault.getCredentials(FIXTURE_SERVER) != null)
            } finally {
                setupClient.close()
                scope.cancel()
            }
            // Remove plaintext JSON references as early as possible; no password enters a UI action.
            fixture.remove("password")
            fixture.remove("sshPassword")
        }
    }

    @After fun cleanup() {
        if (systemPickerOpen) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            repeat(3) {
                if (automation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true) {
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(300) // One Back may close the drawer before the picker itself.
                }
            }
        }
        scenario?.close()
        scenario = null
        if (::settingsPrefs.isInitialized) previousSettings?.let { restore(settingsPrefs, it) }
        if (::securePrefs.isInitialized) previousSecure?.let { restore(securePrefs, it) }
        previousSettings = null
        previousSecure = null
        // Keep requested screenshots and the synthetic SAF artifact name in app cache.
    }

    @Test fun deviceTerminalAndFilesUseRealActivityNavigation() = safe {
        listOf("live-device.png", "live-terminal.png", "live-terminal-window.png", "live-terminal-window-evidence.json", "live-files.png")
            .forEach { File(context.cacheDir, it).delete() }
        launchAndOpenDevice()
        compose.runOnIdle {
            assertTrue("Device card unexpectedly opened a desktop session", model.client.desktopState.value.device == null && !model.client.desktopState.value.handshakeComplete)
            assertTrue("Device card unexpectedly started terminal or files", model.terminalController == null && model.filesController == null)
        }
        screenshot("live-device.png")

        stage = "select Terminal tab using Compose"
        tab(R.string.device_terminal)
        compose.waitUntil(35_000) { model.terminalController?.state?.value?.ready == true }
        compose.runOnIdle {
            renderer = requireNotNull(requireNotNull(model.terminalController).renderer(activity)) {
                "Live terminal renderer failed to initialize"
            }
        }
        compose.waitUntil(15_000) { evaluateBoolean("typeof window.meshTerminal === 'object'") }
        stage = "send fixture printf through the real terminal renderer bridge"
        val id = UUID.randomUUID().toString().replace("-", "")
        val marker = "MC_UI_$id"
        val command = "printf 'MC_UI_%s\\n' '$id'\r"
        compose.runOnIdle { renderer.key(command) }
        // Only a boolean leaves the renderer; raw terminal output is neither asserted nor logged.
        val containsMarker = "(() => {window.meshTerminal.selectAll();const ok=window.meshTerminal.selection().includes(${JSONObject.quote(marker)});window.meshTerminal.clearSelection();return ok;})()"
        compose.waitUntil(30_000) { evaluateBoolean(containsMarker) }

        stage = "recreate the real Activity with a connected terminal"
        val originalModel = model
        val originalTerminal = requireNotNull(model.terminalController)
        val originalRenderer = renderer
        recreate()
        compose.waitUntil(10_000) { model.deviceTab == DeviceTab.Terminal && model.terminalController?.state?.value?.ready == true }
        compose.runOnIdle {
            assertTrue("Activity recreation replaced its ViewModel", model === originalModel)
            assertTrue("Activity recreation replaced its terminal controller", model.terminalController === originalTerminal)
            renderer = requireNotNull(requireNotNull(model.terminalController).renderer(activity)) {
                "Live terminal renderer is unavailable after recreation"
            }
            assertTrue("Activity recreation replaced its terminal renderer", renderer === originalRenderer)
        }
        assertTrue("Activity recreation lost or repeated previous terminal output", evaluateBoolean(markerOccursOnce(marker)))
        stage = "send a new marker after Activity recreation without replaying the old command"
        val nextId = UUID.randomUUID().toString().replace("-", "")
        val nextMarker = "MC_UI_$nextId"
        compose.runOnIdle { renderer.key("printf 'MC_UI_%s\\n' '$nextId'\r") }
        compose.waitUntil(30_000) { evaluateBoolean(markerOccursOnce(nextMarker)) }
        assertTrue("Old terminal command was replayed after recreation", evaluateBoolean(markerOccursOnce(marker)))
        terminalWindowScreenshot()
        screenshot("live-terminal.png")

        stage = "return from terminal using its Compose Back button"
        compose.onNodeWithText(context.getString(R.string.terminal_back)).performClick()
        compose.waitUntil(5_000) { model.deviceTab == DeviceTab.Overview && model.terminalController == null && model.selectedDeviceId == agentNodeId }
        stage = "select Files tab using Compose"
        tab(R.string.device_files)
        compose.waitUntil(35_000) { model.filesController?.state?.value?.let { it.connected && !it.busy && it.entries.isNotEmpty() } == true }
        // Follow real folder dialogs, rather than calling controller.browse from the test.
        openFolder("tmp", "/tmp")
        openFolder("mesh-fixture", FIXTURE_DIRECTORY)
        compose.waitUntil(10_000) { model.filesController?.state?.value?.entries?.any { it.name == "fixture-юникод.txt" } == true }

        stage = "prepare a pending download before Activity recreation"
        val originalFiles = requireNotNull(model.filesController)
        val selected = originalFiles.state.value.entries.single { it.name == "fixture-юникод.txt" }
        assertTrue("Could not prepare a fixture download", originalFiles.prepareDownload(selected))
        recreate()
        compose.waitUntil(10_000) { model.deviceTab == DeviceTab.Files && model.filesController?.state?.value?.connected == true }
        compose.runOnIdle {
            assertTrue("Files recreation replaced the ViewModel", model === originalModel)
            assertTrue("Files recreation replaced the pending controller", model.filesController === originalFiles)
            assertTrue("Files recreation lost the current directory", originalFiles.state.value.path == FIXTURE_DIRECTORY)
        }
        stage = "continue the pending download after Activity recreation"
        val destination = File.createTempFile("live-recreated-download-", ".bin", context.cacheDir)
        try {
            originalFiles.downloadChosen(Uri.fromFile(destination))
            compose.waitUntil(30_000) {
                originalFiles.state.value.let { it.connected && !it.busy && it.message == UiText.resource(R.string.files_download_complete) }
            }
            val expected = "isolated mesh fixture\n".toByteArray(Charsets.UTF_8)
            assertTrue("Continued fixture download has an incorrect length", destination.length() == expected.size.toLong())
            assertTrue("Continued fixture download has an incorrect SHA-256", MessageDigest.isEqual(sha256(expected), sha256(destination.readBytes())))
        } finally { destination.delete() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        screenshot("live-files.png")
    }

    /** Separate from recreation: this launches the platform picker and never stubs its result. */
    @Test fun systemCreateDocumentDownloadsFixture() {
        // App PackageManager.resolveActivity is filtered by package visibility on current Android.
        // Query the instrumented device's resolver as shell, without changing production permissions.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cmd package resolve-activity --brief -a android.intent.action.CREATE_DOCUMENT -c android.intent.category.OPENABLE -t application/octet-stream",
        )
        val provider = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.readText().take(4096)
        }
        assumeTrue("No system ACTION_CREATE_DOCUMENT provider is installed", !provider.contains("No activity found", ignoreCase = true))
        assertTrue("System document provider lookup failed instead of confirming absence", provider.lineSequence().any {
            it.trim().matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))
        })
        safe {
            launchAndOpenDevice()
            tab(R.string.device_files)
            compose.waitUntil(35_000) { model.filesController?.state?.value?.let { it.connected && !it.busy && it.entries.isNotEmpty() } == true }
            openFolder("tmp", "/tmp")
            openFolder("mesh-fixture", FIXTURE_DIRECTORY)
            stage = "launch actual ACTION_CREATE_DOCUMENT from the Compose file dialog"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("fixture-юникод.txt"))
            compose.onNodeWithText("fixture-юникод.txt").performClick()
            compose.onNodeWithText(context.getString(R.string.files_download)).performClick()
            systemPickerOpen = true
            stage = "choose Downloads in the system document picker"
            awaitAccessibility { it.packageName?.toString()?.endsWith("documentsui") == true }
            chooseSystemDownloads()
            stage = "set a unique fixture document name in the real system picker"
            val name = "mesh-live-${UUID.randomUUID().toString().replace("-", "")}.txt"
            // Record the exact owned name before Save, also allowing cleanup after a later failure.
            File(context.cacheDir, "live-saf-created-name.txt").writeText(name)
            val filename = awaitAccessibility {
                it.viewIdResourceName == "android:id/title" && it.className?.toString() == "android.widget.EditText" &&
                    it.isEnabled && hasAncestorId(it, ":id/container_save")
            }
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
            assertTrue("Could not name the fixture document", filename.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            awaitAccessibility { it.viewIdResourceName == "android:id/title" && it.className?.toString() == "android.widget.EditText" && it.text?.toString() == name }
            val save = awaitAccessibility {
                it.viewIdResourceName == "android:id/button1" && it.isEnabled && it.isClickable && hasAncestorId(it, ":id/container_save")
            }
            assertTrue("Could not save the fixture document", clickAccessibility(save))
            stage = "complete the real SAF stream download after the picker returns"
            compose.waitUntil(40_000) {
                model.filesController?.state?.value?.let { it.connected && !it.busy && it.message == UiText.resource(R.string.files_download_complete) } == true
            }
            compose.waitUntil(10_000) { activity.hasWindowFocus() }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onNodeWithText(context.getString(R.string.files_download_complete)).assertIsDisplayed()
            systemPickerOpen = false
            // The generated document is an intentional acceptance artifact, not a broad cleanup target.
            // Record only its synthetic name so the caller can locate/remove exactly this one document.
        }
    }

    private fun launchAndOpenDevice() {
        stage = "launch MainActivity with prepared fixture credentials"
        scenario = ActivityScenario.launch(MainActivity::class.java)
        bindActivity()
        compose.waitUntil(45_000) {
            val state = model.client.clientState.value
            state.socketConnected && !state.requiresSignIn && state.devicesCurrent && state.devices.any { it.nodeId == agentNodeId }
        }
        stage = "open device card using Compose"
        compose.onNodeWithText(agentName).performScrollTo().performClick()
        compose.waitUntil(5_000) { model.selectedDeviceId == agentNodeId && model.deviceTab == DeviceTab.Overview }
    }

    private fun bindActivity() {
        requireNotNull(scenario).onActivity {
            activity = it
            model = ViewModelProvider(it)[MeshSessionViewModel::class.java]
        }
    }
    private fun recreate() { requireNotNull(scenario).recreate(); bindActivity(); compose.waitForIdle() }

    private fun markerOccursOnce(marker: String): String =
        "(() => {window.meshTerminal.selectAll();const ok=window.meshTerminal.selection().split(${JSONObject.quote(marker)}).length===2;window.meshTerminal.clearSelection();return ok;})()"

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun awaitAccessibility(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < until) {
            findAccessibility(predicate)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("System document provider control was not found at $stage")
    }

    private fun findAccessibility(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let { pending.add(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
        }
        return null
    }

    private fun hasAncestorId(node: AccessibilityNodeInfo, suffix: String): Boolean {
        var current = node.parent
        repeat(12) {
            val ancestor = current ?: return false
            if (ancestor.viewIdResourceName?.endsWith(suffix) == true) return true
            current = ancestor.parent
        }
        return false
    }

    private fun isDownloadsLabel(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString()?.trim()?.lowercase(Locale.ROOT) in setOf("downloads", "download", "загрузки", "скачанные файлы")

    private fun chooseSystemDownloads() {
        // Observed on emulator-5556: breadcrumb_text and toolbar both say Downloads but are NOT
        // clickable. The actual drawer root is android:id/title under documentsui:id/roots_list.
        val drawerOpen = findAccessibility { it.viewIdResourceName?.endsWith(":id/roots_list") == true } != null
        val alreadyDownloads = findAccessibility { it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true && isDownloadsLabel(it) } != null
        if (alreadyDownloads && !drawerOpen) return
        if (!drawerOpen) {
            val roots = awaitAccessibility {
                val description = it.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                it.isClickable && it.isEnabled && hasAncestorId(it, ":id/toolbar") &&
                    description in setOf("show roots", "показать корни", "показать папки", "открыть панель навигации")
            }
            assertTrue("Could not open the system document locations", clickAccessibility(roots))
        }
        val downloads = awaitAccessibility {
            it.viewIdResourceName == "android:id/title" && isDownloadsLabel(it) && hasAncestorId(it, ":id/roots_list")
        }
        assertTrue("Could not choose the actual Downloads drawer item", clickAccessibility(downloads))
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            val current = findAccessibility { it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true && isDownloadsLabel(it) }
            val roots = findAccessibility { it.viewIdResourceName?.endsWith(":id/roots_list") == true }
            if (current != null && roots == null) return
            Thread.sleep(100)
        }
        throw AssertionError("Downloads was not confirmed after selecting its actual drawer item")
    }

    private fun clickAccessibility(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.isEnabled) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = candidate.parent
        }
        return false
    }

    private fun tab(label: Int) {
        compose.onNode(hasText(context.getString(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performScrollTo().performClick()
    }

    private fun openFolder(name: String, path: String) {
        stage = "navigate fixture folder $path using Compose"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText(context.getString(R.string.files_open)).performClick()
        compose.waitUntil(15_000) { model.filesController?.state?.value?.let { it.connected && !it.busy && it.path == path } == true }
    }

    private fun evaluateBoolean(script: String): Boolean {
        val complete = CountDownLatch(1)
        var answer = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderer.view.evaluateJavascript(script) { answer = it == "true"; complete.countDown() }
        }
        assertTrue("Fixture renderer JavaScript callback timed out", complete.await(5, TimeUnit.SECONDS))
        return answer
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Fixture screenshot has no pixels", bitmap.width > 0 && bitmap.height > 0)
        File(context.cacheDir, name).outputStream().use { stream ->
            assertTrue("Could not save fixture PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    /** Capture the composited display, independently of Compose captureToImage and its root tree. */
    private fun terminalWindowScreenshot() {
        stage = "capture the real terminal window after recreation and compositor readiness"
        compose.waitForIdle()
        val terminalRect = Rect()
        val windowOrigin = IntArray(2)
        compose.runOnIdle {
            val view = renderer.view
            val decor = activity.window.decorView
            assertTrue("Terminal WebView is not attached to the recreated Activity", view.isAttachedToWindow && view.rootView === decor && view.windowToken == decor.windowToken)
            assertTrue("Terminal WebView has no visible viewport", view.isShown && view.getGlobalVisibleRect(terminalRect) && terminalRect.width() > 0 && terminalRect.height() > 0)
            decor.getLocationOnScreen(windowOrigin)
        }
        val drawn = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderer.view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    // Let two real UI frames commit after the WebView's visual-state barrier.
                    renderer.view.postOnAnimation { renderer.view.postOnAnimation { drawn.countDown() } }
                }
            })
        }
        assertTrue("Terminal visual frame did not become ready", drawn.await(10, TimeUnit.SECONDS))
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation could not capture the real terminal window"
        }
        // Save the original bitmap before asserting pixel evidence. No crop, redraw or image edits.
        File(context.cacheDir, "live-terminal-window.png").outputStream().use {
            assertTrue("Could not save the actual terminal window PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        // Re-query semantics after recreate; never retain an old root or node interaction.
        val header = compose.onNodeWithText(agentName).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        compose.onNodeWithText(context.getString(R.string.terminal_connected)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.terminal_back)).assertIsDisplayed()
        val headerRect = Rect(header.left.toInt() + windowOrigin[0], header.top.toInt() + windowOrigin[1],
            header.right.toInt() + windowOrigin[0], header.bottom.toInt() + windowOrigin[1])
        // Some Android versions return a hardware bitmap. Copy only for read-only inspection;
        // the artifact above always remains the original, untouched UiAutomation capture.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else bitmap
        val headerPixels = nonBackgroundPixels(readable, headerRect)
        val terminalPixels = nonBackgroundPixels(readable, terminalRect)
        File(context.cacheDir, "live-terminal-window-evidence.json").writeText(JSONObject()
            .put("width", bitmap.width).put("height", bitmap.height)
            .put("headerRect", headerRect.toShortString()).put("terminalRect", terminalRect.toShortString())
            .put("headerNonBackgroundPixels", headerPixels).put("terminalNonBackgroundPixels", terminalPixels)
            .put("currentActivityAttachment", true).put("webViewVisualStateReady", true).toString())
        assertTrue("Actual terminal screenshot has a blank device header", headerPixels >= 32)
        assertTrue("Actual terminal screenshot has a blank terminal viewport despite DOM output", terminalPixels >= 128)
    }

    /** Read-only pixel check catches the observed flat screenshot; human visual review is still needed. */
    private fun nonBackgroundPixels(bitmap: Bitmap, requested: Rect): Int {
        val region = Rect(requested)
        assertTrue("Screenshot region lies outside the current display", region.intersect(0, 0, bitmap.width, bitmap.height))
        val colors = HashMap<Int, Int>()
        var count = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                // Quantization ignores negligible antialiasing/compositor noise in a flat background.
                val color = bitmap.getPixel(x, y) and 0x00F8F8F8
                colors[color] = (colors[color] ?: 0) + 1
                count++
            }
        }
        return count - (colors.values.maxOrNull() ?: 0)
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
    }

    private fun safe(action: () -> Unit) {
        try { action() }
        catch (error: Throwable) {
            if (systemPickerOpen) runCatching { recordPickerFailure() }
            if (error is AssertionError || error !is Exception) throw error
            // Never attach a network exception cause: its message can contain a credentialed URL.
            throw AssertionError("Live navigation failed at $stage (${error.javaClass.simpleName})")
        }
    }

    private fun recordPickerFailure() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return
        // Diagnostic screenshots are restricted to the fixture's system picker, never login UI.
        if (root.packageName?.toString()?.endsWith("documentsui") != true) return
        automation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "live-saf-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val nodes = JSONArray()
        val safeLabels = setOf("downloads", "download", "загрузки", "скачанные файлы", "save", "сохранить", "show roots", "save to", "files in downloads", "no items")
        while (pending.isNotEmpty() && nodes.length() < 300) {
            val node = pending.removeFirst()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val description = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            nodes.put(JSONObject().put("id", node.viewIdResourceName.orEmpty()).put("class", node.className?.toString().orEmpty())
                .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled).put("clickable", node.isClickable)
                .put("selected", node.isSelected).put("bounds", bounds.toShortString())
                .put("text", if (!node.isPassword && !node.isEditable && text in safeLabels) text else "[redacted]")
                .put("description", if (description in safeLabels) description else "[redacted]"))
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
        }
        File(context.cacheDir, "live-saf-failure-tree.json").writeText(JSONObject().put("stage", stage).put("nodes", nodes).toString())
    }

    companion object {
        private const val FIXTURE_URL = "http://10.0.2.2:19847/fixture"
        private const val FIXTURE_SERVER = "https://10.0.2.2:19846"
        private const val FIXTURE_DIRECTORY = "/tmp/mesh-fixture"
    }
}
