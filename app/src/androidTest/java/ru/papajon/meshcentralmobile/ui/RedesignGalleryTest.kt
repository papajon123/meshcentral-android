package ru.papajon.meshcentralmobile.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshGroup
import ru.papajon.meshcentralmobile.mesh.MeshRights
import ru.papajon.meshcentralmobile.mesh.MeshServerCapabilities
import ru.papajon.meshcentralmobile.mesh.RemoteDesktopState
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.DeviceScreen
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.screens.LockScreen
import ru.papajon.meshcentralmobile.ui.screens.LoginContent
import ru.papajon.meshcentralmobile.ui.screens.OnboardingScreen
import ru.papajon.meshcentralmobile.ui.screens.RemoteDesktopScreen
import ru.papajon.meshcentralmobile.ui.screens.ServerSettingsScreen
import ru.papajon.meshcentralmobile.ui.screens.SettingsScreen
import ru.papajon.meshcentralmobile.ui.screens.session.SessionOptionsSheet
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Opt-in before/after redesign gallery rendered from the production Composables.
 *
 * Opt-in: `-e redesignGallery true`; phase: `-e galleryPhase before|after` (default `before`).
 * One PNG is written per screen as `targetContext.cacheDir/redesign-<phase>-<screen>.png`, plus
 * `redesign-<phase>-manifest.json` listing every PNG that was actually created.
 *
 * Everything is synthetic: reserved `.example` endpoints, synthetic `MeshDevice` instances, no
 * MeshCentralClient connection, no MeshCentral server, no settings/credential write, no network.
 * The real production client is constructed for the session screen but is never configured or
 * connected, so it only exposes its genuine "no active session" state.
 */
@OptIn(ExperimentalTestApi::class)
class RedesignGalleryTest {
    // No Activity is launched before the @Before opt-in check.
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val frame = mutableStateOf<Screen?>(Screen.Onboarding)
    private val captures = mutableListOf<Capture>()
    private val savedUrls = mutableListOf<String>()
    private val submittedPins = mutableListOf<String>()
    private var refreshCalls = 0
    private val loginState = MeshClientState(
        connectionState = ConnectionState.Failed,
        lastError = UiText.resource(R.string.login_status_failed),
    )
    private val sessionOptionsState = RemoteDesktopState(
        connectionState = ConnectionState.Failed,
        device = buildServer(),
    )
    private var previousCapturePixels: IntArray? = null
    private var captureLanguage: String = GALLERY_LANGUAGE
    private var captureDark: Boolean = true
    private var phase: String = DEFAULT_PHASE
    private lateinit var localizedContext: Context
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private lateinit var activity: ComponentActivity
    private var ownerScope: CoroutineScope? = null
    private var meshClient: MeshCentralClient? = null

    private enum class Screen(val part: String) {
        Onboarding("onboarding"),
        ServerInvalid("server-settings-invalid"),
        ServerValid("server-settings-valid"),
        Login("login"),
        LockEmpty("lock-empty"),
        LockError("lock-error"),
        Settings("settings-has-pin"),
        DeviceAvailable("device-overview-available"),
        DeviceNoPermission("device-overview-no-permission"),
        DevicesEmpty("devices-empty"),
        DevicesLoaded("devices-loaded"),
        RemoteDesktop("remote-desktop-disconnected"),
        SessionOptions("session-options-sheet"),
    }

    private data class Capture(
        val screen: String,
        val file: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    @Before fun requireExplicitGalleryOptIn() {
        assumeTrue(
            "Redesign gallery is opt-in only: -e redesignGallery true",
            InstrumentationRegistry.getArguments().getString("redesignGallery") == "true",
        )
        val requested = InstrumentationRegistry.getArguments()
            .getString("galleryPhase")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PHASE
        assertTrue("galleryPhase must be before or after", requested == "before" || requested == "after")
        phase = requested
        captureLanguage = InstrumentationRegistry.getArguments().getString("galleryLocale") ?: GALLERY_LANGUAGE
        captureDark = InstrumentationRegistry.getArguments().getString("galleryTheme") != "light"
        localizedContext = contextFor(captureLanguage)
    }

    @After fun closeGallery() {
        disposeFixtures()
    }

    private fun launchGallery() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity {
                activity = it
                it.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.window.isNavigationBarContrastEnforced = false
                }
                it.setContent { GalleryContent() }
            }
        }
    }

    /** Scoped acceptance for quick unlock; unrelated session sheets retain their own gallery. */
    @Test fun rendersQuickUnlockAndHomeIntoPngs() {
        clearPreviousOutputs()
        launchGallery()
        show(Screen.LockEmpty)
        requireVisible("0", scroll = true)
        capture(Screen.LockEmpty)
        show(Screen.LockError)
        typePin("6042")
        waitForText(string(R.string.lock_invalid_pin))
        requireVisible(string(R.string.lock_invalid_pin), scroll = true)
        capture(Screen.LockError)
        show(Screen.DevicesLoaded)
        requireVisible("Build server", scroll = true)
        capture(Screen.DevicesLoaded)
        show(Screen.Settings)
        compose.onNodeWithTag("lock-timeout-row").performScrollTo().assertIsDisplayed()
        capture(Screen.Settings)
        assertEquals(4, captures.size)
        writeManifest()
    }

    @Test fun rendersEveryReachableProductionScreenIntoPngs() {
        clearPreviousOutputs()
        launchGallery()

        // 1. Onboarding (introduction plus the production server form).
        show(Screen.Onboarding)
        requireVisible(string(R.string.app_name))
        requireVisible(string(R.string.server_title), scroll = true)
        requireVisible(string(R.string.server_save), scroll = true)
        requireVisible(string(R.string.app_name), scroll = true)
        capture(Screen.Onboarding)

        // 2. Server settings: a real Save tap with an empty address must reject the input.
        savedUrls.clear()
        show(Screen.ServerInvalid)
        requireVisible(string(R.string.server_title))
        clickText(string(R.string.server_save), scroll = true)
        waitForText(string(R.string.runtime_validation_url_required))
        requireVisible(string(R.string.runtime_validation_url_required), scroll = true)
        compose.runOnIdle { assertTrue("Invalid address must not invoke the save callback", savedUrls.isEmpty()) }
        capture(Screen.ServerInvalid)

        // 3. Server settings: a real Save tap with a reserved example HTTPS address must persist.
        savedUrls.clear()
        show(Screen.ServerValid)
        requireVisible(string(R.string.server_title))
        clickText(string(R.string.server_save), scroll = true)
        waitForText(string(R.string.server_saved))
        requireVisible(string(R.string.server_saved), scroll = true)
        compose.runOnIdle { assertEquals(listOf(DEMO_SERVER), savedUrls) }
        capture(Screen.ServerValid)

        // 4. Login through the existing no-network content seam.
        show(Screen.Login)
        requireVisible(string(R.string.login_title))
        requireVisible(string(R.string.login_username), scroll = true)
        requireVisible(string(R.string.login_password), scroll = true)
        requireVisible(string(R.string.login_action_sign_in), scroll = true)
        requireVisible(string(R.string.login_title), scroll = true)
        capture(Screen.Login)

        // 5. Lock screen, empty PIN.
        show(Screen.LockEmpty)
        requireVisible(string(R.string.lock_title))
        requireVisible(string(R.string.lock_subtitle))
        requireVisible("0", scroll = true)
        capture(Screen.LockEmpty)

        // 6. Lock screen after a synthetic rejected PIN callback.
        submittedPins.clear()
        show(Screen.LockError)
        typePin("6042")
        waitForText(string(R.string.lock_invalid_pin))
        requireVisible(string(R.string.lock_invalid_pin), scroll = true)
        compose.runOnIdle { assertEquals("6042", submittedPins.last()) }
        capture(Screen.LockError)

        // 7. Settings with a synthetic configured PIN.
        show(Screen.Settings)
        requireVisible(string(R.string.settings_title))
        requireVisible(string(R.string.settings_message_pin_configured), scroll = true)
        requireVisible(string(R.string.settings_update_action), scroll = true)
        requireExists(string(R.string.settings_pin_status_set))
        requireVisible(string(R.string.settings_title), scroll = true)
        capture(Screen.Settings)

        // 8. Device overview for a synthetic device with full rights.
        show(Screen.DeviceAvailable)
        requireVisible(buildServer().name)
        requireVisible(string(R.string.device_os), scroll = true)
        requireVisible(string(R.string.relay_available), scroll = true)
        capture(Screen.DeviceAvailable)

        // 9. Device overview for a synthetic device without desktop/session permission.
        show(Screen.DeviceNoPermission)
        requireVisible(deniedDevice().name)
        requireVisible(string(R.string.runtime_capability_no_permission), scroll = true)
        requireVisible(string(R.string.devices_capability_reason_no_permission), scroll = true)
        capture(Screen.DeviceNoPermission)

        // 10. Devices list, authenticated but empty.
        show(Screen.DevicesEmpty)
        requireVisible(string(R.string.devices_title))
        requireVisible(string(R.string.devices_empty_title))
        requireVisible(string(R.string.devices_empty_action), scroll = true)
        clickText(string(R.string.devices_empty_action), scroll = true)
        compose.runOnIdle { assertEquals("Empty-state refresh remains reachable", 1, refreshCalls) }
        requireVisible(string(R.string.devices_empty_title), scroll = true)
        capture(Screen.DevicesEmpty)

        // 11. Devices list loaded with synthetic `.example` server data.
        show(Screen.DevicesLoaded)
        requireVisible(string(R.string.devices_title))
        requireVisible(buildServer().name)
        requireVisible(SYNTHETIC_GROUP)
        requireVisible("Workstation", scroll = true)
        capture(Screen.DevicesLoaded)

        // 12. Remote desktop screen in its real no-active-session state.
        createUnconnectedClient()
        show(Screen.RemoteDesktop)
        requireVisible(string(R.string.session_empty_title))
        requireVisible(string(R.string.session_empty_description))
        requireVisible(string(R.string.session_empty_action))
        capture(Screen.RemoteDesktop)

        // 13. Public session sheet seam, rendered with a synthetic desktop state.
        show(Screen.SessionOptions)
        waitForText(string(R.string.session_options_title))
        requireVisible(string(R.string.session_options_title))
        requireVisible(string(R.string.session_options_stream_quality), scroll = true)
        requireVisible(string(R.string.session_options_preset_balanced), scroll = true)
        requireExists(string(R.string.session_options_connection))
        capture(Screen.SessionOptions)

        assertEquals("Every listed screen must create exactly one PNG", Screen.entries.size, captures.size)
        writeManifest()
        val manifest = File(app.cacheDir, "redesign-$phase-manifest.json")
        assertTrue("Gallery JSON manifest was not created", manifest.isFile && manifest.length() > 0)
        captures.forEach { capture ->
            assertTrue("Gallery PNG disappeared: ${capture.file}", File(app.cacheDir, capture.file).isFile)
        }
    }

    @Composable private fun GalleryContent() {
        val current = frame.value ?: return
        val configuration = localizedContext.resources.configuration
        val insetModifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).clipToBounds()
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            MeshCentralMobileTheme(darkTheme = captureDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    key(current) {
                        when (current) {
                            Screen.Onboarding -> OnboardingScreen(
                                settings = AppSettings(),
                                onServerUrlSaved = {},
                                onAllowHttpInDebugChanged = {},
                                onTrustSelfSignedChanged = {},
                            )

                            Screen.ServerInvalid -> ServerSettingsScreen(
                                settings = AppSettings(serverUrl = ""),
                                onServerUrlSaved = { savedUrls += it },
                                onAllowHttpInDebugChanged = {},
                                onTrustSelfSignedChanged = {},
                            )

                            Screen.ServerValid -> ServerSettingsScreen(
                                settings = AppSettings(serverUrl = DEMO_SERVER),
                                onServerUrlSaved = { savedUrls += it },
                                onAllowHttpInDebugChanged = {},
                                onTrustSelfSignedChanged = {},
                            )

                            Screen.Login -> LoginContent(
                                clientState = loginState,
                                serverUrl = DEMO_SERVER,
                                onLogin = { _, _, _ -> },
                                onSubmitCode = { _, _ -> },
                                onStoredLogin = {},
                                onRequestCode = {},
                                onEditServer = {},
                            )

                            Screen.LockEmpty, Screen.LockError -> LockScreen(
                                biometricEnabled = false,
                                pinLength = 4,
                                onPinSubmit = { pin -> submittedPins += pin; false },
                                onBiometricUnlockRequested = {},
                            )

                            Screen.Settings -> SettingsScreen(
                                settings = AppSettings(serverUrl = DEMO_SERVER),
                                serverUrl = DEMO_SERVER,
                                hasPin = true,
                                onScreenshotBlockingChanged = {},
                                onBiometricUnlockChanged = {},
                                onLockTimeoutChanged = {},
                                onPinSaved = {},
                                onPinCleared = {},
                                onLockNow = {},
                                onClearSession = {},
                                modifier = insetModifier,
                            )

                            Screen.DeviceAvailable -> DeviceScreen(
                                device = buildServer(),
                                server = MeshServerCapabilities(features2 = 512),
                                current = true,
                                selected = DeviceTab.Overview,
                                onSelect = {},
                                onBack = {},
                                onWebPanel = {},
                                modifier = insetModifier,
                            ) {}

                            Screen.DeviceNoPermission -> DeviceScreen(
                                device = deniedDevice(),
                                server = MeshServerCapabilities(features2 = 512),
                                current = true,
                                selected = DeviceTab.Overview,
                                onSelect = {},
                                onBack = {},
                                onWebPanel = {},
                                modifier = insetModifier,
                            ) {}

                            Screen.DevicesEmpty -> DevicesContent(
                                clientState = emptyState(),
                                favorites = emptyList(),
                                onAddFavorite = { _, _ -> },
                                onRemoveFavorite = {},
                                onConnect = {},
                                onRefresh = { refreshCalls++ },
                                onOpenSettings = {},
                                onSignIn = {},
                                modifier = insetModifier,
                                onOpenDevice = {},
                            )

                            Screen.DevicesLoaded -> DevicesContent(
                                clientState = syntheticState(),
                                favorites = emptyList(),
                                onAddFavorite = { _, _ -> },
                                onRemoveFavorite = {},
                                onConnect = {},
                                onRefresh = {},
                                onOpenSettings = {},
                                onSignIn = {},
                                modifier = insetModifier,
                                onOpenDevice = {},
                            )

                            Screen.RemoteDesktop -> RemoteDesktopScreen(
                                meshClient = requireNotNull(meshClient) { "RemoteDesktopScreen requires its production client" },
                                onFullscreenChanged = {},
                                onBrowseDevices = {},
                            )

                            Screen.SessionOptions -> SessionOptionsSheet(
                                state = sessionOptionsState,
                                fullscreen = false,
                                zoom = 1f,
                                onZoomIn = {},
                                onZoomOut = {},
                                onResetZoom = {},
                                debugVisible = false,
                                onDebugVisibleChange = {},
                                onQuality = { _, _ -> },
                                onReconnect = {},
                                onShowHelp = {},
                                onToggleFullscreen = {},
                                onHideChrome = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun show(screen: Screen) {
        compose.runOnIdle { frame.value = screen }
        compose.waitForIdle()
    }

    private fun requireVisible(text: String, scroll: Boolean = false) {
        compose.waitForIdle()
        if (scroll) {
            val before = compose.onAllNodesWithText(text, useUnmergedTree = true)
            assertTrue("Expected label is missing: '$text'", before.fetchSemanticsNodes().isNotEmpty())
            before[0].performScrollTo()
        }
        compose.waitForIdle()
        val matches = compose.onAllNodesWithText(text, useUnmergedTree = true)
        assertTrue("Expected label is missing: '$text'", matches.fetchSemanticsNodes().isNotEmpty())
        matches[0].assertIsDisplayed()
    }

    private fun requireExists(text: String) {
        compose.waitForIdle()
        assertTrue(
            "Expected control is missing: '$text'",
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun clickText(text: String, scroll: Boolean = false) {
        compose.waitForIdle()
        // Buttons and keypad keys expose their OnClick action on the merged node.
        val matches = compose.onAllNodesWithText(text)
        assertTrue("Cannot click missing control: '$text'", matches.fetchSemanticsNodes().isNotEmpty())
        val node = matches[0]
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun typePin(pin: String) {
        pin.forEach { digit -> clickText(digit.toString(), scroll = true) }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(TEXT_TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A ready semantics tree can precede the first composited window. Wait for a real animation
     * callback, then require a non-null UiAutomation screenshot and a complete PNG file.
     */
    private fun capture(screen: Screen) {
        compose.waitForIdle()
        val output = File(app.cacheDir, "redesign-$phase-${screen.part}.png")
        if (output.exists()) assertTrue("Could not clear stale gallery PNG", output.delete())

        val deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT_MILLIS
        var bitmap: Bitmap? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (true) {
            awaitFrame()
            val candidate = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            if (candidate != null) {
                val readable = if (candidate.config == Bitmap.Config.HARDWARE) candidate.copy(Bitmap.Config.ARGB_8888, false)!! else candidate
                val samples = mutableListOf<Int>()
                // Ignore the clock/system bars: a different clock must not validate a stale app frame.
                for (y in 100 until readable.height - 100 step 8) for (x in 0 until readable.width step 8) samples += readable.getPixel(x, y)
                val pixels = samples.toIntArray()
                val fresh = previousCapturePixels?.contentEquals(pixels) != true
                // Android 8 may expose a blank activity window before its first real frame.
                // Include full-width rows (tablet text can sit near either edge), excluding bars.
                val contentColors = mutableSetOf<Int>()
                for (y in readable.height / 10 until readable.height * 9 / 10 step 8) {
                    for (x in 0 until readable.width step 8) {
                        contentColors += readable.getPixel(x, y)
                    }
                }
                val contentDrawn = contentColors.size >= 16
                if (readable !== candidate) readable.recycle()
                if (fresh && contentDrawn) {
                    previousCapturePixels = pixels
                    bitmap = candidate
                    break
                }
                candidate.recycle()
            }
            if (SystemClock.uptimeMillis() >= deadline) break
        }
        val screenshot = requireNotNull(bitmap) {
            "UiAutomation returned no screenshot for ${screen.part} within ${FRAME_TIMEOUT_MILLIS}ms"
        }
        // HARDWARE bitmaps cannot be read/pixel-inspected and are not a reliable PNG source.
        val frameBitmap = if (screenshot.config == Bitmap.Config.HARDWARE) {
            requireNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) {
                "Could not convert the hardware screenshot for ${screen.part}"
            }
        } else {
            screenshot
        }
        try {
            assertTrue("Screenshot has no pixels for ${screen.part}", frameBitmap.width > 0 && frameBitmap.height > 0)
            output.outputStream().use { stream ->
                assertTrue(
                    "Could not encode gallery PNG for ${screen.part}",
                    frameBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream),
                )
            }
            assertTrue("Gallery PNG was not created for ${screen.part}", output.isFile)
            assertTrue("Gallery PNG is empty for ${screen.part}", output.length() > PNG_SIGNATURE.size)
            val header = ByteArray(PNG_SIGNATURE.size)
            val read = output.inputStream().use { it.read(header) }
            assertEquals("Gallery PNG header is incomplete for ${screen.part}", PNG_SIGNATURE.size, read)
            assertArrayEquals("Gallery file is not a PNG for ${screen.part}", PNG_SIGNATURE, header)
            captures += Capture(screen.part, output.name, frameBitmap.width, frameBitmap.height, output.length())
            writeManifest()
        } finally {
            frameBitmap.recycle()
            if (frameBitmap !== screenshot) screenshot.recycle()
        }
    }

    private fun awaitFrame() {
        val ready = CountDownLatch(1)
        compose.runOnIdle {
            val view = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                view.viewTreeObserver.registerFrameCommitCallback { ready.countDown() }
                view.invalidate()
            } else {
                view.postOnAnimation { view.postOnAnimation { ready.countDown() } }
            }
        }
        assertTrue("Gallery window produced no animation frame", ready.await(FRAME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
    }

    private fun createUnconnectedClient() {
        compose.runOnIdle {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            ownerScope = scope
            // Constructing the real client opens no socket and writes no storage. It is never
            // configured, authenticated, or asked to connect a desktop.
            meshClient = MeshCentralClient(SecureVault(app), scope)
        }
    }

    private fun disposeFixtures() {
        if (scenario != null) {
            runCatching { compose.runOnIdle { frame.value = null } }
            runCatching { compose.waitForIdle() }
            runCatching { compose.runOnIdle { meshClient?.close() } }
        }
        meshClient = null
        ownerScope?.coroutineContext?.get(Job)?.let { job ->
            runCatching { runBlocking { withTimeout(CLEANUP_TIMEOUT_MILLIS) { job.cancelAndJoin() } } }
        }
        ownerScope = null
        scenario?.close()
        scenario = null
    }

    private fun clearPreviousOutputs() {
        app.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("redesign-$phase-") &&
                (file.name.endsWith(".png") || file.name.endsWith(".json"))
            ) {
                file.delete()
            }
        }
    }

    private fun writeManifest() {
        val screenshots = JSONArray()
        captures.forEach { capture ->
            screenshots.put(
                JSONObject()
                    .put("screen", capture.screen)
                    .put("file", capture.file)
                    .put("width", capture.width)
                    .put("height", capture.height)
                    .put("bytes", capture.bytes),
            )
        }
        val gaps = JSONArray()
        GAPS.forEach { gaps.put(it) }
        val manifest = JSONObject()
            .put("phase", phase)
            .put("theme", if (captureDark) "dark" else "light")
            .put("locale", captureLanguage)
            .put("fontScale", localizedContext.resources.configuration.fontScale)
            .put("screenWidthDp", localizedContext.resources.configuration.screenWidthDp)
            .put("directory", app.cacheDir.absolutePath)
            .put("screenshots", screenshots)
            .put("gaps", gaps)
        File(app.cacheDir, "redesign-$phase-manifest.json").writeText(manifest.toString(2))
    }

    private fun string(id: Int): String = localizedContext.getString(id)

    private fun contextFor(tag: String): Context {
        val locales = LocaleList.forLanguageTags(tag)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return app.createConfigurationContext(configuration)
    }

    private companion object {
        const val DEFAULT_PHASE = "before"
        const val GALLERY_LANGUAGE = "en"
        const val DEMO_SERVER = "https://meshcentral.example/"
        const val SYNTHETIC_GROUP = "Lab"
        const val TEXT_TIMEOUT_MILLIS = 5_000L
        const val FRAME_TIMEOUT_MILLIS = 5_000L
        const val CLEANUP_TIMEOUT_MILLIS = 5_000L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())

        val GAPS = listOf(
            "RemoteDesktopScreen accepts only a live MeshCentralClient. The connected/reconnect overlay " +
                "needs an injected RemoteDesktopState, which has no public seam, so the gallery captures " +
                "the real 'No active session' state instead of inventing a test API.",
            "Settings exposes no public dialog or menu composable. Its debug WebView overlay needs a " +
                "network-backed WebView and is excluded; SessionOptionsSheet is captured as the public " +
                "sheet seam instead.",
        )

        fun buildServer() = MeshDevice(
            nodeId = "node//gallery-build",
            meshId = "mesh//gallery-lab",
            name = "Build server",
            meshName = SYNTHETIC_GROUP,
            conn = 1,
            pwr = 1,
            osDescription = "Debian GNU/Linux",
            mtype = 2,
            agentId = 6,
            agentCaps = 7,
            rights = MeshRights.FULL,
        )

        fun deniedDevice() = MeshDevice(
            nodeId = "node//gallery-locked",
            meshId = "mesh//gallery-lab",
            name = "Locked workstation",
            meshName = SYNTHETIC_GROUP,
            conn = 1,
            pwr = 1,
            osDescription = "Windows 11",
            mtype = 2,
            agentId = 4,
            agentCaps = 7,
            rights = MeshRights.NO_DESKTOP,
        )

        fun emptyState() = MeshClientState(
            connectionState = ConnectionState.Authenticated,
            serverName = "meshcentral.example.com",
            groups = emptyList(),
            devices = emptyList(),
            socketConnected = true,
            requiresSignIn = false,
            devicesCurrent = true,
        )

        fun syntheticState() = MeshClientState(
            connectionState = ConnectionState.Authenticated,
            serverName = "meshcentral.example.com",
            groups = listOf(MeshGroup("mesh//gallery-lab", SYNTHETIC_GROUP)),
            devices = listOf(
                buildServer(),
                MeshDevice(
                    nodeId = "node//gallery-workstation",
                    meshId = "mesh//gallery-lab",
                    name = "Workstation",
                    meshName = SYNTHETIC_GROUP,
                    conn = 0,
                    osDescription = "Windows 11",
                    mtype = 2,
                    agentId = 4,
                    agentCaps = 7,
                    rights = MeshRights.FULL,
                ),
            ),
            socketConnected = true,
            requiresSignIn = false,
            devicesCurrent = true,
        )
    }
}
