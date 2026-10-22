package ru.papajon.meshcentralmobile.workspace

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.*
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.management.*
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.*
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.notifications.*
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.transfer.*
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.components.SessionWorkspaceSheet
import ru.papajon.meshcentralmobile.ui.screens.companion.CompanionScreen
import ru.papajon.meshcentralmobile.ui.screens.companion.CompanionTestTags
import ru.papajon.meshcentralmobile.ui.screens.management.DeviceInspectorScreen
import ru.papajon.meshcentralmobile.ui.screens.management.DeviceInspectorTestTags
import ru.papajon.meshcentralmobile.ui.screens.management.DeviceInspectorTab
import ru.papajon.meshcentralmobile.ui.screens.notifications.NotificationSettingsSheet
import ru.papajon.meshcentralmobile.ui.screens.notifications.NotificationSettingsTestTags
import ru.papajon.meshcentralmobile.ui.screens.profiles.ServerProfilesSheet
import ru.papajon.meshcentralmobile.ui.screens.profiles.ServerProfilesTestTags
import ru.papajon.meshcentralmobile.ui.screens.transfers.TransferQueueSheet
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Opt-in PNG gallery for workspace/management surfaces.
 * `-e roadmapGallery true -e galleryLocale en|ru|zh-Hans|ar -e galleryTheme light|dark`.
 * Writes `cacheDir/roadmap-<locale>-<theme>-<screen>.png` plus the matching JSON manifest.
 * Synthetic `.example.test` data only; the synthetic notification profile is removed in [closeGallery].
 */
@OptIn(ExperimentalTestApi::class)
class RoadmapGalleryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val frame = mutableStateOf<Screen?>(Screen.Profiles)
    private val captures = mutableListOf<Capture>()
    private val profileError = MutableStateFlow<String?>(null)
    private val sessions = MutableStateFlow(
        listOf(
            OpenSession("node/lab|Terminal", NODE_ID, "Lab Workstation", DeviceTab.Terminal),
            OpenSession("node/team|Files", NODE_ID, "Team Workstation", DeviceTab.Files),
        ),
    )
    private val transport = FakeCompanionTransport(NODE_ID)
    private val uploader = InternalQueuedUploader()
    private var previousPixels: IntArray? = null
    private var locale = DEFAULT_LOCALE
    private var dark = true
    private var snapshot: Map<String, *>? = null
    private lateinit var localizedContext: Context
    private lateinit var activity: ComponentActivity
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var queueScope: CoroutineScope? = null
    private var queue: TransferQueue? = null
    private var files: FilesController? = null
    private var relay: FakeRelayConnection? = null
    private var companionScope: CoroutineScope? = null
    private var companionController: CompanionController? = null
    private var notificationScope: CoroutineScope? = null
    private var notificationClient: MeshCentralClient? = null
    private var notificationController: NotificationController? = null
    private var notificationProfileId: String? = null

    private enum class Screen {
        Profiles, Workspace, DeviceDetails, DeviceServices, DeviceProcesses,
        DeviceErrorEmpty, Companion, Notifications, Transfers,
    }
    private data class Capture(val file: String, val width: Int, val height: Int, val bytes: Long)
    private data class Inspector(val services: List<ManagedService>, val processes: List<ManagedProcess>, val error: String?)

    @Before fun requireExplicitGalleryOptIn() {
        // First statement: no Activity, prefs, network or fixture data before opt-in.
        assumeTrue(
            "Roadmap gallery is opt-in only: -e roadmapGallery true",
            InstrumentationRegistry.getArguments().getString("roadmapGallery") == "true",
        )
        locale = InstrumentationRegistry.getArguments().getString("galleryLocale") ?: DEFAULT_LOCALE
        assertTrue("galleryLocale must be one of $LOCALES", locale in LOCALES)
        val requestedTheme = InstrumentationRegistry.getArguments().getString("galleryTheme") ?: "dark"
        assertTrue("galleryTheme must be light or dark", requestedTheme == "light" || requestedTheme == "dark")
        dark = requestedTheme == "dark"
        localizedContext = contextFor(locale)
        snapshot = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).all.toMap()
    }

    @After fun closeGallery() {
        if (scenario != null) {
            runCatching { compose.runOnIdle { frame.value = null } }
            runCatching { compose.waitForIdle() }
        }
        runCatching { queue?.close() }
        runCatching { files?.close() }
        runCatching { queueScope?.cancel() }
        runCatching { companionController?.close() }
        runCatching { companionScope?.cancel() }
        notificationProfileId?.let { id ->
            // This synthetic profile only: no clearAll(), global PIN or unrelated namespace.
            runCatching { NotificationController.disableLocal(app, id) }
            runCatching { SecureVault(app, id).clearScopedSecret(NotificationContract.LABEL) }
        }
        runCatching { notificationClient?.close() }
        runCatching { notificationScope?.cancel() }
        try {
            restoreAppSettings()
        } finally {
            runCatching { scenario?.close() }
            scenario = null
        }
    }

    @Test fun rendersRoadmapProductionSurfaces() {
        clearPreviousOutputs()
        prepareCompanion()
        prepareNotifications()
        prepareTransfers()
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity { windowActivity ->
                activity = windowActivity
                // Modal windows resolve Android resources from their Activity as well as LocalContext.
                @Suppress("DEPRECATION")
                windowActivity.resources.updateConfiguration(
                    localizedContext.resources.configuration, windowActivity.resources.displayMetrics,
                )
                windowActivity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    windowActivity.window.isNavigationBarContrastEnforced = false
                }
                windowActivity.setContent { GalleryContent() }
            }
        }
        compose.waitForIdle()

        show(Screen.Profiles)
        requireTag(ServerProfilesTestTags.Sheet)
        requireVisible(string(R.string.profile_sheet_title))
        requireVisible("Lab")
        requireVisible("Team", scroll = true)
        requireVisible(string(R.string.profile_sheet_title), scroll = true)
        capture("server-profiles-list")
        clickTag(ServerProfilesTestTags.AddAction, scroll = true)
        requireTag(ServerProfilesTestTags.AddForm, scroll = true)
        typeTag(ServerProfilesTestTags.NameField, "Team")
        typeTag(ServerProfilesTestTags.UrlField, "https://team.example.test/")
        clickTag(ServerProfilesTestTags.SubmitAdd, scroll = true)
        waitForTag(ServerProfilesTestTags.ErrorBanner)
        requireTag(ServerProfilesTestTags.AddForm, scroll = true)
        requireTag(ServerProfilesTestTags.ErrorBanner, scroll = true)
        capture("server-profiles-add-error")

        show(Screen.Workspace)
        waitForText(string(R.string.workspace_sessions))
        requireVisible("Lab Workstation")
        requireVisible("Team Workstation", scroll = true)
        requireVisible(string(R.string.workspace_sessions), scroll = true)
        capture("session-workspace-two-slots")
        clickText(string(R.string.device_close_session), scroll = true)
        waitForText(string(R.string.workspace_close_explanation, "Lab Workstation"))
        requireVisible(string(R.string.workspace_close_explanation, "Lab Workstation"))
        capture("session-workspace-close-confirm")

        show(Screen.DeviceDetails)
        requireVisible("Workstation")
        requireVisible(string(R.string.manage_section_system), scroll = true)
        capture("inspector-details")

        show(Screen.DeviceServices)
        clickTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services))
        waitForText("Print Spooler")
        requireVisible("Print Spooler")
        clickTag(DeviceInspectorTestTags.serviceMenu("spooler"), scroll = true)
        clickTag(DeviceInspectorTestTags.serviceAction("spooler", ServiceAction.Stop))
        waitForTag(DeviceInspectorTestTags.ConfirmDialog)
        requireVisible(
            string(R.string.manage_confirm_service_title, string(R.string.manage_action_stop), "Print Spooler"),
        )
        capture("inspector-services-confirmation")

        show(Screen.DeviceProcesses)
        clickTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Processes))
        waitForText("agent")
        requireVisible("agent")
        capture("inspector-processes")

        show(Screen.DeviceErrorEmpty)
        clickTag(DeviceInspectorTestTags.tab(DeviceInspectorTab.Services))
        waitForText(string(R.string.manage_error))
        requireVisible(string(R.string.manage_error))
        requireVisible(string(R.string.manage_services_empty))
        capture("inspector-error-empty-services")

        show(Screen.Companion)
        compose.waitUntil(TEXT_TIMEOUT) { companionController!!.state.value.available }
        scrollToTag(CompanionTestTags.Content, CompanionTestTags.DiagnosticsSection)
        requireTag(CompanionTestTags.DiagnosticsSection)
        requireVisible(string(R.string.companion_diagnostics_title), scroll = true)
        requireVisible("Debian GNU/Linux 12", scroll = true)
        scrollToTag(CompanionTestTags.Content, CompanionTestTags.DiagnosticsSection)
        requireVisible(string(R.string.companion_diagnostics_title))
        capture("companion-diagnostics")
        scrollToTag(CompanionTestTags.Content, CompanionTestTags.QuickHelp)
        requireTag(CompanionTestTags.QuickHelp)
        requireVisible(string(R.string.companion_help_title), scroll = true)
        capture("companion-quick-help")
        compose.onNodeWithTag(CompanionTestTags.Content).performScrollToIndex(0)
        compose.waitForIdle()
        scrollToTag(CompanionTestTags.Content, CompanionTestTags.RunDiagnostics)
        clickTag(CompanionTestTags.RunDiagnostics)
        waitForTag(CompanionTestTags.DisclosureDialog)
        requireVisible(string(R.string.companion_disclosure_title))
        assertTrue("Disclosure capture must not start a job", transport.ops.none { it == "startJob" })
        capture("companion-disclosure")

        show(Screen.Notifications)
        requireVisible(string(R.string.notification_sheet_title))
        requireVisible(string(R.string.notification_status_disabled))
        requireTag(NotificationSettingsTestTags.Sheet)
        capture("notification-disabled")
        scrollToTag(NotificationSettingsTestTags.Sheet, NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT))
        requireTag(NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT))
        compose.onNodeWithTag(NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT)).assertIsOn()
        compose.onNodeWithTag(NotificationSettingsTestTags.filter(NotificationEventKind.JOB_COMPLETED)).assertIsOff()
        requireVisible(string(R.string.notification_filters_title), scroll = true)
        scrollToTag(NotificationSettingsTestTags.Sheet, NotificationSettingsTestTags.QuietStart)
        requireTag(NotificationSettingsTestTags.QuietStart)
        requireVisible(string(R.string.notification_quiet_title), scroll = true)
        scrollToTag(NotificationSettingsTestTags.Sheet, NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT))
        requireVisible(string(R.string.notification_filters_title), scroll = true)
        // A tablet can fit both regions without scrolling; change a real preference so the
        // second capture proves a new committed frame rather than requiring scroll movement.
        compose.onNodeWithTag(NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT)).performClick()
        compose.onNodeWithTag(NotificationSettingsTestTags.filter(NotificationEventKind.DEVICE_ALERT)).assertIsOff()
        capture("notification-filters-quiet-hours")

        driveQueue()
        assertTrue("Fake relay must never connect", relay!!.connects == 0)
        show(Screen.Transfers)
        requireVisible(string(R.string.transfer_queue_title), scroll = true)
        requireVisible(string(R.string.transfer_status_succeeded), scroll = true)
        requireVisible(string(R.string.transfer_status_failed), scroll = true)
        requireVisible(string(R.string.transfer_status_active), scroll = true)
        requireVisible(string(R.string.transfer_queue_title), scroll = true)
        capture("transfer-queue-mixed")
        clickText(string(R.string.transfer_retry), scroll = true)
        waitForText(string(R.string.transfer_retry_title))
        requireVisible(string(R.string.transfer_retry_title))
        capture("transfer-retry-confirmation")

        assertEquals("Every roadmap screen must create exactly one PNG", 15, captures.size)
        writeManifest()
        val manifest = File(app.cacheDir, "${prefix}manifest.json")
        assertTrue("Roadmap JSON manifest was not created", manifest.isFile && manifest.length() > 0)
        captures.forEach { assertTrue("Roadmap PNG disappeared: ${it.file}", File(app.cacheDir, it.file).isFile) }
    }

    @Composable private fun GalleryContent() {
        val current = frame.value ?: return
        val configuration = localizedContext.resources.configuration
        val error by profileError.collectAsState()
        val openSessions by sessions.collectAsState()
        CompositionLocalProvider(
            LocalActivityResultRegistryOwner provides activity,
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides if (configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            MeshCentralMobileTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    key(current) {
                        when (current) {
                            Screen.Profiles -> ServerProfilesSheet(
                                profiles = PROFILES, activeProfileId = PROFILES.first().id,
                                onActivate = {}, onCreate = { _, url -> profileError.value = "Server rejected $url" },
                                onRename = { _, _ -> }, onDelete = {}, onClose = {}, error = error,
                            )
                            Screen.Workspace -> SessionWorkspaceSheet(
                                sessions = openSessions, onSelect = {},
                                onCloseSession = { id -> sessions.value = sessions.value.filterNot { it.id == id } },
                                onDismiss = {},
                            )
                            Screen.DeviceDetails, Screen.DeviceServices,
                            Screen.DeviceProcesses, Screen.DeviceErrorEmpty -> {
                                val fixture = inspector(current)
                                DeviceInspectorScreen(
                                    snapshot = SNAPSHOT, services = fixture.services, processes = fixture.processes,
                                    loading = false, error = fixture.error,
                                    availablePowerActions = setOf(DevicePowerAction.Restart),
                                    canManageServices = true, onRefresh = {}, onLoadServices = {},
                                    onLoadProcesses = {}, onPower = {}, onServiceAction = { _, _ -> }, onBack = {},
                                )
                            }
                            Screen.Companion -> CompanionScreen(
                                controller = requireNotNull(companionController), deviceName = DEVICE, onBack = {},
                            )
                            Screen.Notifications -> NotificationSettingsSheet(
                                controller = requireNotNull(notificationController), onClose = {},
                            )
                            Screen.Transfers -> TransferQueueSheet(
                                queue = requireNotNull(queue), onClose = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun inspector(screen: Screen): Inspector = when (screen) {
        Screen.DeviceDetails -> Inspector(emptyList(), emptyList(), null)
        Screen.DeviceServices -> Inspector(SERVICES, emptyList(), null)
        Screen.DeviceProcesses -> Inspector(SERVICES, PROCESSES, null)
        else -> Inspector(emptyList(), emptyList(), string(R.string.manage_error))
    }

    private fun prepareCompanion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        companionScope = scope
        companionController = CompanionController(scope, CompanionClient(transport::request), NODE_ID)
    }

    private fun prepareNotifications() {
        val profile = SettingsRepository(app).createProfile("Lab", SERVER_URL)
        notificationProfileId = profile.id
        val store = requireNotNull(NotificationStore.open(app, profile.id, SERVER_URL, NOTIFICATION_USER))
        store.saveSettings(
            NotificationSettings(
                kinds = setOf(NotificationEventKind.DEVICE_ALERT, NotificationEventKind.DEVICE_ONLINE, NotificationEventKind.JOB_FAILED),
                quietHours = QuietHours(enabled = true, startHour = 22, endHour = 7),
            ),
        )
        store.commitCheck(
            feed = listOf(
                NotificationFeedItem("fixture-alert", NotificationEventKind.DEVICE_ALERT, NODE_ID, null, FIXTURE_TIME, FIXTURE_TIME + DAY),
                NotificationFeedItem("fixture-online", NotificationEventKind.DEVICE_ONLINE, NODE_ID, null, FIXTURE_TIME + 60_000L, FIXTURE_TIME + DAY),
            ),
            pending = emptySet(), cursor = 2, checkedAt = FIXTURE_TIME + 120_000L,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        notificationScope = scope
        val client = MeshCentralClient(SecureVault(app, profile.id), scope)
        notificationClient = client
        notificationController = NotificationController(app, profile.id, SERVER_URL, NOTIFICATION_USER, client, scope)
    }

    private fun prepareTransfers() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        queueScope = scope
        val fakeRelay = FakeRelayConnection()
        relay = fakeRelay
        val controller = FilesController(fakeRelay, scope, app)
        files = controller
        val transferQueue = TransferQueue(scope, uploader)
        queue = transferQueue
        val target = TransferTarget("roadmap-lab", "Lab", NODE_ID, DEVICE, "", controller)
        assertTrue(
            "Synthetic queue fixture was rejected",
            transferQueue.enqueueConfirmed(
                target,
                listOf(
                    TransferItem(1, contentUri("one"), "one.bin", 2_048),
                    TransferItem(2, contentUri("two"), "two.bin", 1_024),
                    TransferItem(3, contentUri("three"), "three.bin", 4_096),
                ),
            ),
        )
    }

    private fun driveQueue() {
        val first = runBlocking { withTimeout(TEXT_TIMEOUT) { uploader.calls.receive() } }
        first.complete(TransferOutcome.Success(2_048))
        val second = runBlocking { withTimeout(TEXT_TIMEOUT) { uploader.calls.receive() } }
        second.complete(TransferOutcome.Failed(UiText.resource(R.string.transfer_upload_failed)))
        val third = runBlocking { withTimeout(TEXT_TIMEOUT) { uploader.calls.receive() } }
        assertFalse("Active fixture must stay active until cleanup", third.isCompleted)
        runBlocking {
            withTimeout(TEXT_TIMEOUT) {
                while (queue?.state?.value?.map { it.status } !=
                    listOf(TransferStatus.Succeeded, TransferStatus.Failed, TransferStatus.Active)
                ) {
                    delay(10)
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
        val before = compose.onAllNodesWithText(text, useUnmergedTree = true)
        assertTrue("Expected label is missing: '$text'", before.fetchSemanticsNodes().isNotEmpty())
        if (scroll) before[0].performScrollTo()
        compose.waitForIdle()
        val matches = compose.onAllNodesWithText(text, useUnmergedTree = true)
        assertTrue("Expected label is missing: '$text'", matches.fetchSemanticsNodes().isNotEmpty())
        matches[0].assertIsDisplayed()
    }

    private fun requireTag(tag: String, scroll: Boolean = false) {
        compose.waitForIdle()
        val before = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        assertTrue("Expected control is missing: '$tag'", before.fetchSemanticsNodes().isNotEmpty())
        if (scroll) before[0].performScrollTo()
        compose.waitForIdle()
        val matches = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        assertTrue("Expected control is missing: '$tag'", matches.fetchSemanticsNodes().isNotEmpty())
        matches[0].assertIsDisplayed()
    }

    private fun clickText(text: String, scroll: Boolean = false) {
        compose.waitForIdle()
        val matches = compose.onAllNodesWithText(text)
        assertTrue("Cannot click missing control: '$text'", matches.fetchSemanticsNodes().isNotEmpty())
        val node = matches[0]
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickTag(tag: String, scroll: Boolean = false) {
        compose.waitForIdle()
        val matches = compose.onAllNodesWithTag(tag)
        assertTrue("Cannot click missing control: '$tag'", matches.fetchSemanticsNodes().isNotEmpty())
        val node = matches[0]
        if (scroll) node.performScrollTo()
        node.performClick()
        compose.waitForIdle()
    }

    private fun typeTag(tag: String, value: String) {
        compose.waitForIdle()
        val node = compose.onAllNodesWithTag(tag)[0]
        node.performScrollTo()
        node.performTextInput(value)
        compose.waitForIdle()
    }

    private fun scrollToTag(container: String, target: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(target))
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = compose.waitUntil(TEXT_TIMEOUT) {
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(TEXT_TIMEOUT) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Frame-commit plus pixel-freshness gate; the screenshot itself is never edited. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File(app.cacheDir, "$prefix$name.png")
        if (output.exists()) assertTrue("Could not clear stale roadmap PNG", output.delete())
        val deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT
        var bitmap: Bitmap? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        while (true) {
            awaitFrame()
            val candidate = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
            if (candidate != null) {
                val readable = if (candidate.config == Bitmap.Config.HARDWARE) candidate.copy(Bitmap.Config.ARGB_8888, false)!! else candidate
                val samples = mutableListOf<Int>()
                for (y in 100 until readable.height - 100 step 8) for (x in 0 until readable.width step 8) samples += readable.getPixel(x, y)
                val pixels = samples.toIntArray()
                val fresh = previousPixels?.contentEquals(pixels) != true
                val contentColors = mutableSetOf<Int>()
                for (y in readable.height / 10 until readable.height * 9 / 10 step 8) for (x in 0 until readable.width step 8) contentColors += readable.getPixel(x, y)
                val drawn = contentColors.size >= 16
                if (readable !== candidate) readable.recycle()
                if (fresh && drawn) { previousPixels = pixels; bitmap = candidate; break }
                candidate.recycle()
            }
            if (SystemClock.uptimeMillis() >= deadline) break
        }
        val screenshot = requireNotNull(bitmap) { "UiAutomation returned no fresh screenshot for $name within ${FRAME_TIMEOUT}ms" }
        val frameBitmap = if (screenshot.config == Bitmap.Config.HARDWARE) {
            requireNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) { "Could not convert hardware screenshot for $name" }
        } else screenshot
        try {
            assertTrue("Screenshot has no pixels for $name", frameBitmap.width > 0 && frameBitmap.height > 0)
            output.outputStream().use { stream ->
                assertTrue("Could not encode roadmap PNG for $name", frameBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("Roadmap PNG was not created for $name", output.isFile)
            assertTrue("Roadmap PNG is empty for $name", output.length() > PNG_SIGNATURE.size)
            val header = ByteArray(PNG_SIGNATURE.size)
            val read = output.inputStream().use { it.read(header) }
            assertEquals("Roadmap PNG header is incomplete for $name", PNG_SIGNATURE.size, read)
            assertArrayEquals("Roadmap file is not a PNG for $name", PNG_SIGNATURE, header)
            captures += Capture(output.name, frameBitmap.width, frameBitmap.height, output.length())
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
        assertTrue("Roadmap window produced no animation frame", ready.await(FRAME_TIMEOUT, TimeUnit.MILLISECONDS))
    }

    private fun clearPreviousOutputs() {
        app.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix) && (file.name.endsWith(".png") || file.name.endsWith(".json"))) file.delete()
        }
    }

    private fun writeManifest() {
        val screenshots = JSONArray()
        captures.forEach { capture ->
            screenshots.put(JSONObject().put("filename", capture.file).put("width", capture.width).put("height", capture.height).put("bytes", capture.bytes))
        }
        val manifest = JSONObject()
            .put("prefix", prefix).put("locale", locale).put("theme", themeName)
            .put("fontScale", localizedContext.resources.configuration.fontScale)
            .put("screenWidthDp", localizedContext.resources.configuration.screenWidthDp)
            .put("directory", app.cacheDir.absolutePath).put("screenshots", screenshots)
            .put("gaps", JSONArray().put(GAP))
        File(app.cacheDir, "${prefix}manifest.json").writeText(manifest.toString(2))
    }

    private fun restoreAppSettings() {
        val saved = snapshot ?: return
        val editor = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue("Could not restore app_settings after the gallery", editor.commit())
        snapshot = null
    }

    private fun string(id: Int, vararg args: Any): String = localizedContext.getString(id, *args)
    private fun contextFor(tag: String): Context {
        val locales = LocaleList.forLanguageTags(tag)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locales[0])
        }
        return app.createConfigurationContext(configuration)
    }
    private val prefix: String get() = "roadmap-$locale-$themeName-"
    private val themeName: String get() = if (dark) "dark" else "light"
    private fun contentUri(id: String): Uri = Uri.parse("content://roadmap.example.test/$id")

    private class InternalQueuedUploader : QueuedUploader {
        val calls = Channel<CompletableDeferred<TransferOutcome>>(Channel.UNLIMITED)
        override suspend fun upload(target: TransferTarget, item: TransferItem, onProgress: (Long) -> Unit): TransferOutcome {
            val result = CompletableDeferred<TransferOutcome>()
            calls.trySend(result)
            return result.await()
        }
    }

    private class FakeRelayConnection : RelayConnection {
        var connects = 0
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        override val incoming = Channel<RelayFrame>(1).receiveAsFlow()
        override fun connect() { connects++ }
        override fun sendText(value: String): Boolean = false
        override fun sendBinary(value: ByteArray): Boolean = false
        override fun close() = Unit
    }

    private class FakeCompanionTransport(private val nodeId: String) {
        val ops = CopyOnWriteArrayList<String>()
        suspend fun request(command: JSONObject): JSONObject {
            val op = command.optString("op")
            ops += op
            return when (op) {
                "capabilities" -> ok(JSONObject("""{"protocolVersion":1,"schemaVersion":1,"operations":["startJob"],"templates":[{"templateId":"diagnostics","kind":"read","parameters":[]},{"templateId":"services","kind":"read","parameters":[]},{"templateId":"processes","kind":"read","parameters":[]}],"platforms":["linux"]}"""))
                "listJobs" -> ok(JSONObject().put("jobs", JSONArray().put(diagnosticsJob()).put(servicesJob()).put(processesJob())))
                "events" -> ok(JSONObject("""{"items":[{"id":"fixture-event","sequence":1,"kind":"job-completed","nodeid":"$nodeId","jobId":"$DIAGNOSTICS_ID","time":$FIXTURE_TIME,"expiresAt":${FIXTURE_TIME + DAY}}],"cursor":1,"resetCursor":false}"""))
                "getJob" -> {
                    val id = command.optString("jobId")
                    ok(JSONObject().put("job", listOf(diagnosticsJob(), servicesJob(), processesJob()).first { it.optString("id") == id }))
                }
                else -> JSONObject().put("action", "plugin").put("plugin", "mobilecompanion").put("ok", false).put("error", JSONObject().put("code", "UNKNOWN_OP"))
            }
        }
        private fun diagnosticsJob() = job(DIAGNOSTICS_ID, "diagnostics", 100L, """{"os":"linux","hostname":"workstation.example.test","osDescription":"Debian GNU/Linux 12","uptimeSeconds":93600,"cpu":{"logicalCount":8,"load1":0.42},"memory":{"totalBytes":8589934592,"availableBytes":4294967296,"usedPercent":48},"disks":[{"device":"/dev/vda1","mount":"/","totalBytes":68719476736,"availableBytes":5368709120,"usedPercent":91}],"network":[{"name":"eth0","addresses":["192.0.2.10"]}]}""")
        private fun servicesJob() = job(SERVICES_ID, "services", 200L, """{"os":"linux","items":[{"name":"spooler","displayName":"Print Spooler","state":"Running","startMode":"Automatic"},{"name":"agent","displayName":"Example Agent","state":"Stopped","startMode":"Manual"}],"truncated":false}""")
        private fun processesJob() = job(PROCESSES_ID, "processes", 300L, """{"os":"linux","items":[{"pid":4242,"name":"agent","cpu":1.5,"memory":4096}],"truncated":false}""")
        private fun job(id: String, template: String, offset: Long, result: String) = JSONObject(
            """{"id":"$id","nodeid":"$nodeId","templateId":"$template","parameters":{},"createdAt":${FIXTURE_TIME + offset},"startedAt":${FIXTURE_TIME + offset + 10},"finishedAt":${FIXTURE_TIME + offset + 20},"status":"completed","exitCode":0,"result":$result}""",
        )
    }
}

private const val DEFAULT_LOCALE = "en"
private const val APP_PREFS = "app_settings"
private const val SERVER_URL = "https://meshcentral.example.test/"
private const val NODE_ID = "node/example.test/workstation"
private const val DEVICE = "Workstation"
private const val NOTIFICATION_USER = "gallery-user"
private val FIXTURE_TIME = System.currentTimeMillis() - 3_600_000L
private const val DAY = 86_400_000L
private const val TEXT_TIMEOUT = 5_000L
private const val FRAME_TIMEOUT = 5_000L
private const val DIAGNOSTICS_ID = "mc_fixture_diagnostics"
private const val SERVICES_ID = "mc_fixture_services"
private const val PROCESSES_ID = "mc_fixture_processes"
private val LOCALES = setOf("en", "ru", "zh-Hans", "ar")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
private const val GAP = "NotificationController has no close()/state seam: the gallery pre-seeds NotificationStore for its synthetic profile and captures the real disabled/filters/quiet states; the enabled state would require a real token and is not faked."
private val PROFILES = listOf(
    ServerProfile("lab", "Lab", "https://lab.example.test/"),
    ServerProfile("team", "Team", "https://team.example.test/"),
)
private val SNAPSHOT = DeviceSystemSnapshot(
    deviceName = DEVICE, osName = "Debian GNU/Linux 12", osVersion = "12.5", architecture = "x86_64",
    kernelRelease = "6.1.0-example", cpu = "Example 8-core CPU", model = "Example Workstation",
    memoryModules = listOf(DeviceSystemSnapshot.MemoryModule(8L * 1024 * 1024 * 1024, "3200")),
    disks = listOf(DeviceSystemSnapshot.Disk("Disk 0", "Example NVMe", 64L * 1024 * 1024 * 1024)),
    networkAddresses = listOf(DeviceSystemSnapshot.NetworkAddress("eth0", "192.0.2.10", "IPv4")),
)
private val SERVICES = listOf(
    ManagedService("spooler", "Print Spooler", "Running", "Automatic"),
    ManagedService("agent", "Example Agent", "Stopped", "Manual"),
)
private val PROCESSES = listOf(
    ManagedProcess(4242, "agent", "1.5%", "4 MB"),
    ManagedProcess(5150, "sshd", "0.2%", "3 MB"),
)

private fun ok(data: JSONObject): JSONObject = JSONObject()
    .put("action", "plugin").put("plugin", "mobilecompanion").put("ok", true).put("data", data)
