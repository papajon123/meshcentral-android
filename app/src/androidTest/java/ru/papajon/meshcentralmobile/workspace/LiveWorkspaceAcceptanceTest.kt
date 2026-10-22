package ru.papajon.meshcentralmobile.workspace

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.CompanionClient
import ru.papajon.meshcentralmobile.companion.CompanionDiagnostics
import ru.papajon.meshcentralmobile.companion.CompanionJob
import ru.papajon.meshcentralmobile.companion.CompanionProcesses
import ru.papajon.meshcentralmobile.companion.CompanionTemplate
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.files.FileChange
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.terminal.TerminalController
import ru.papajon.meshcentralmobile.terminal.TerminalWebRenderer
import ru.papajon.meshcentralmobile.transfer.TransferItem
import ru.papajon.meshcentralmobile.transfer.TransferStatus
import ru.papajon.meshcentralmobile.transfer.TransferTarget
import ru.papajon.meshcentralmobile.ui.DeviceTab
import ru.papajon.meshcentralmobile.ui.MeshSessionViewModel

/**
 * Explicit opt-in only: -e liveFixture http://10.0.2.2:19857/fixture.
 * Real Application runtime, real profile-scoped vault and the isolated Linux fixture only.
 * Never prints fixture JSON, credentials, passwords or raw server responses.
 */
@OptIn(ExperimentalTestApi::class)
class LiveWorkspaceAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var workspace: ConnectionWorkspace
    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var recentPrefs: SharedPreferences
    private lateinit var updatePrefs: SharedPreferences
    private var previousSettings: Map<String, *>? = null
    private var previousSecure: Map<String, *>? = null
    private var previousRecent: Map<String, *>? = null
    private var previousUpdate: Map<String, *>? = null
    private lateinit var initialProfileId: String
    private lateinit var secondProfileId: String
    private lateinit var profileName: String
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var model: MeshSessionViewModel
    private lateinit var agentNodeId: String
    private lateinit var agentName: String
    private lateinit var sshNodeId: String
    private var activeFiles: FilesController? = null
    private val localFiles = mutableListOf<File>()
    private val pendingRemoteNames = mutableListOf<String>()
    private var stage = "fixture bootstrap"

    @Before fun prepare() {
        val argument = InstrumentationRegistry.getArguments().getString("liveFixture")
        assumeTrue("Live fixture was not explicitly selected", argument == FIXTURE_URL)
        assumeTrue("Live fixture is debug-only", BuildConfig.DEBUG)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        application = context.applicationContext as Application
        safe {
            stage = "read the isolated fixture bootstrap"
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
            sshNodeId = fixture.getString("sshNodeId")
            assertTrue("Unexpected fixture agent identifier", agentNodeId.startsWith("node//"))
            assertTrue("Unexpected fixture SSH identifier", sshNodeId.startsWith("node//"))
            val fixtureUsername = fixture.getString("username")
            val fixturePassword = fixture.getString("password")

            // Preserve the debug app's existing settings and encrypted values in memory only.
            settingsPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            securePrefs = context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE)
            recentPrefs = context.getSharedPreferences("mesh_recent_devices", Context.MODE_PRIVATE)
            updatePrefs = context.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE)
            previousSettings = settingsPrefs.all.toMap()
            previousSecure = securePrefs.all.toMap()
            previousRecent = recentPrefs.all.toMap()
            previousUpdate = updatePrefs.all.toMap()
            val settings = SettingsRepository(context)
            initialProfileId = settings.activeProfile.id
            profileName = settings.activeProfile.name
            val vault = SecureVault(context, initialProfileId)
            vault.clearPin()
            vault.clearCredentials()
            settings.renameProfile(initialProfileId, "Lab")
            settings.updateServerUrl(FIXTURE_SERVER)
            settings.updateTrustSelfSignedCertificates(true)
            settings.updateBlockScreenshots(false)
            settings.updateBiometricUnlock(false)
            settings.completeOnboarding()

            stage = "authenticate the real profile-scoped runtime"
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val setupClient = MeshCentralClient(vault, scope)
            try {
                setupClient.setAllowSelfSignedCertificates(true)
                setupClient.login(FIXTURE_SERVER, fixtureUsername, fixturePassword)
                val ready = runBlocking {
                    withTimeout(45_000) {
                        setupClient.clientState.first { state ->
                            state.socketConnected && !state.requiresSignIn && state.devicesCurrent &&
                                state.devices.any { it.nodeId == agentNodeId } && state.devices.any { it.nodeId == sshNodeId }
                        }
                    }
                }
                agentName = ready.devices.single { it.nodeId == agentNodeId }.name
                assertTrue("Fixture login did not persist in the profile vault", vault.getCredentials(FIXTURE_SERVER) != null)
            } finally {
                setupClient.close()
                scope.cancel()
            }

            // A real, inactive profile used solely for the profile-bound close path.
            secondProfileId = if (settings.profilesState.value.size < ProfileCatalog.MAX_PROFILES) {
                settings.createProfile(
                    "live-acceptance-${UUID.randomUUID().toString().take(8)}",
                    FIXTURE_SERVER,
                    onboardingCompleted = true,
                ).id
            } else {
                settings.profilesState.value.first { it.id != initialProfileId }.id
            }

            // ConnectionWorkspace registers a network callback, so construct it on the main Looper.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                workspace = ConnectionWorkspace.get(application)
                workspace.closeAll()
                workspace.message = null
                runCatching { workspace.client.logout(clearStoredCredentials = false) }
                runCatching { workspace.bindIdentity(FIXTURE_SERVER, null) }
                if (workspace.profileId != initialProfileId) workspace.switchProfile(initialProfileId)
                workspace.client.setAllowSelfSignedCertificates(true)
            }

            fixture.remove("password")
            fixture.remove("sshPassword")
        }
    }

    @After fun cleanup() {
        scenario?.close()
        scenario = null
        val files = activeFiles
        if (files != null && pendingRemoteNames.isNotEmpty()) {
            runCatching { runBlocking { deleteRemoteFiles(files, pendingRemoteNames.toList()) } }
        }
        if (::workspace.isInitialized) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    workspace.closeAll()
                    workspace.message = null
                    runCatching { workspace.client.logout(clearStoredCredentials = false) }
                    runCatching { workspace.bindIdentity(FIXTURE_SERVER, null) }
                }
            }
        }
        if (::workspace.isInitialized && ::initialProfileId.isInitialized &&
            workspace.profileId != initialProfileId
        ) {
            // Switch before restoring preferences so any legacy-vault migration is overwritten too.
            runCatching {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    runCatching { workspace.switchProfile(initialProfileId) }
                    runCatching { workspace.client.logout(clearStoredCredentials = false) }
                }
            }
        }
        if (::settingsPrefs.isInitialized) previousSettings?.let { restore(settingsPrefs, it) }
        if (::securePrefs.isInitialized) previousSecure?.let { restore(securePrefs, it) }
        if (::recentPrefs.isInitialized) previousRecent?.let { restore(recentPrefs, it) }
        if (::updatePrefs.isInitialized) previousUpdate?.let { restore(updatePrefs, it) }
        previousSettings = null
        previousSecure = null
        previousRecent = null
        previousUpdate = null
        localFiles.forEach { it.delete() }
        localFiles.clear()
        pendingRemoteNames.clear()
    }

    @Test fun twoSlotsSurviveNavigationCapacityAndScopeChanges() = safe {
        stage = "launch MainActivity after runtime login"
        launch()
        openAgent()
        val legacyWebView = knownLegacyWebView()

        stage = "open the agent terminal and files in two slots"
        val terminal = openTerminal(legacyWebView)
        val files = openFiles()
        activeFiles = files
        assertTwoSessions(terminal, files, legacyWebView)

        stage = "selecting the first controller does not replace the second"
        compose.runOnIdle { model.selectSession(sessionId(terminal)) }
        compose.waitUntil(10_000) { model.terminalController === terminal && model.filesController == null }
        compose.runOnIdle {
            assertTrue("Selecting the terminal removed the Files slot", workspace.sessions.size == 2)
            assertTrue("Selecting the terminal replaced the Files controller", workspace.sessions.any { it.files === files })
        }
        compose.runOnIdle { model.selectSession(sessionId(files)) }
        compose.waitUntil(10_000) { model.filesController === files }
        compose.runOnIdle {
            assertTrue("Selecting Files removed the terminal slot", workspace.sessions.size == 2)
            assertTrue("Selecting Files replaced the terminal controller", workspace.sessions.any { it.terminal === terminal })
        }

        stage = "reject a third SSH attempt before it can connect"
        val sshDevice = model.client.clientState.value.devices.single { it.nodeId == sshNodeId }
        compose.runOnIdle { model.openDevice(sshDevice) }
        compose.waitUntil(10_000) { model.selectedDeviceId == sshNodeId }
        assertTrue("Fixture SSH is not attemptable", sshDevice.sessionAvailability(
            RelayProtocol.SshTerminal,
            model.client.clientState.value.serverCapabilities,
        ).canAttempt)
        var accepted = false
        compose.runOnIdle { accepted = model.selectDeviceTab(DeviceTab.Ssh) }
        compose.runOnIdle {
            assertFalse("A third session was admitted", accepted)
            assertTrue("Capacity rejection did not surface the capacity message",
                workspace.message == UiText.resource(R.string.workspace_capacity))
            assertTrue("Rejected SSH attempt still occupies a slot", workspace.sessions.size == 2)
            assertTrue("Rejected SSH attempt created a third controller",
                workspace.sessions.none { it.tab == DeviceTab.Ssh || it.tab == DeviceTab.Sftp })
        }
        compose.runOnIdle { workspace.message = null }

        stage = "close Files only and keep the terminal ready"
        compose.runOnIdle {
            model.openDevice(model.client.clientState.value.devices.single { it.nodeId == agentNodeId })
            model.selectSession(sessionId(files))
        }
        compose.waitUntil(10_000) { model.filesController === files }
        compose.runOnIdle { model.closeSession(sessionId(files)) }
        compose.waitUntil(10_000) { !files.state.value.connected && workspace.sessions.none { it.files === files } }
        compose.runOnIdle {
            assertTrue("Closing Files removed the terminal slot", workspace.sessions.any { it.terminal === terminal })
            if (legacyWebView) assertExpectedTerminalUnavailable(terminal)
            else assertTrue("Terminal was not ready after Files closed", terminal.state.value.ready)
        }
        if (legacyWebView) {
            compose.runOnIdle {
                assertFalse("Unavailable legacy terminal accepted input", terminal.sendInput("printf 'unexpected\\n'\r"))
            }
        } else {
            sendKnownHarmlessPrintf(terminal)
        }

        stage = "reopen the second slot after an explicit close"
        val filesAgain = openFiles()
        assertNotSame("Reopening Files reused the closed controller", files, filesAgain)
        activeFiles = filesAgain
        assertTwoSessions(terminal, filesAgain, legacyWebView)

        stage = "Compose Back from Files keeps both live sessions"
        compose.onNodeWithText(context.getString(R.string.files_back)).performClick()
        compose.waitUntil(10_000) { model.deviceTab == DeviceTab.Overview && model.selectedDeviceId == agentNodeId }
        compose.runOnIdle {
            assertTrue("Compose Back closed a live session", workspace.sessions.size == 2)
            assertTrue("Compose Back closed the Files transport", filesAgain.state.value.connected)
        }

        stage = "device root Back keeps both live sessions"
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { model.selectedDeviceId == null }
        compose.runOnIdle { assertTrue("Device root Back closed a live session", workspace.sessions.size == 2) }

        stage = "browser-style app pause and resume keeps both live sessions"
        scenario?.moveToState(Lifecycle.State.CREATED)
        scenario?.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { model.selectedDeviceId == null }
        compose.runOnIdle { assertTrue("Lifecycle pause closed a live session", workspace.sessions.size == 2) }
        compose.onNodeWithText(agentName).performScrollTo().performClick()
        compose.waitUntil(10_000) { model.selectedDeviceId == agentNodeId }
        compose.runOnIdle {
            model.selectSession(sessionId(filesAgain))
            assertTrue("Reopened device did not restore the live Files controller", model.filesController === filesAgain)
        }

        stage = "account scope change closes every controller"
        compose.waitUntil(10_000) { workspace.identity != null }
        compose.runOnIdle { model.bindIdentity(FIXTURE_SERVER, "live-other-account") }
        compose.waitUntil(10_000) { workspace.sessions.isEmpty() && model.selectedDeviceId == null }
        runBlocking { await(10_000) { !filesAgain.state.value.connected } }
        compose.runOnIdle {
            assertFalse("Account change leaked a ready terminal", terminal.state.value.ready)
            assertTrue("Account change leaked the Files controller", !filesAgain.state.value.connected)
        }

        stage = "open two slots again after the account-scoped close"
        openAgent()
        val terminalAgain = openTerminal(legacyWebView)
        val filesAgainTwo = openFiles()
        activeFiles = filesAgainTwo
        assertTwoSessions(terminalAgain, filesAgainTwo, legacyWebView)

        stage = "profile switch closes every controller"
        val previousClient = workspace.client
        compose.runOnIdle { model.switchProfile(secondProfileId) }
        compose.waitUntil(10_000) { workspace.profileId == secondProfileId && workspace.sessions.isEmpty() }
        runBlocking { await(10_000) { !filesAgainTwo.state.value.connected } }
        compose.runOnIdle {
            assertTrue("Profile switch kept the previous runtime client", workspace.client !== previousClient)
            assertFalse("Profile switch leaked a ready terminal", terminalAgain.state.value.ready)
            assertTrue("Profile switch leaked the Files controller", !filesAgainTwo.state.value.connected)
        }

        stage = "restore the fixture profile selection"
        compose.runOnIdle { model.switchProfile(initialProfileId) }
        compose.waitUntil(10_000) { workspace.profileId == initialProfileId && workspace.sessions.isEmpty() }
    }

    @Test fun companionDiagnosticsAndProcessesSurviveControlReconnect() = safe {
        stage = "launch for companion capabilities"
        launch()
        val companion = CompanionClient(model.client)
        runBlocking {
            val capabilities = companion.capabilities()
            assertTrue("Companion capabilities do not advertise diagnostics",
                capabilities.supports(CompanionTemplate.DIAGNOSTICS))
            assertTrue("Companion capabilities do not advertise processes",
                capabilities.supports(CompanionTemplate.PROCESSES))

            stage = "start and verify the diagnostics job"
            val diagnostics = startAndAwait(companion, CompanionTemplate.DIAGNOSTICS)
            assertTrue("Diagnostics job was not verified complete", diagnostics.verifiedComplete)
            val parsedDiagnostics = diagnostics.result as? CompanionDiagnostics
            assertNotNull("Diagnostics job returned no structured result", parsedDiagnostics)
            val diagnosticResult = requireNotNull(parsedDiagnostics)
            assertTrue("Diagnostics OS is missing", diagnosticResult.os?.isNotBlank() == true)
            assertTrue("Diagnostics memory is invalid",
                diagnosticResult.memory.totalBytes == null || diagnosticResult.memory.totalBytes!! > 0)

            stage = "start and verify the processes job"
            val processes = startAndAwait(companion, CompanionTemplate.PROCESSES)
            assertTrue("Processes job was not verified complete", processes.verifiedComplete)
            val parsedProcesses = processes.result as? CompanionProcesses
            assertNotNull("Processes job returned no structured result", parsedProcesses)
            val processResult = requireNotNull(parsedProcesses)
            assertTrue("Processes result is empty", processResult.items.isNotEmpty())
            assertTrue("Processes result contains invalid rows",
                processResult.items.all { it.pid >= 0 && it.name.isNotBlank() })

            stage = "reconnect the real control client and read the stored server result"
            model.client.logout(clearStoredCredentials = false)
            assertTrue("Profile-scoped credentials were unavailable for control reconnect",
                model.client.loginWithStoredCredentials(FIXTURE_SERVER))
            awaitControlReady(model.client)
            val afterReconnect = CompanionClient(model.client).getJob(diagnostics.id)
            assertTrue("Stored diagnostics result did not survive control reconnect", afterReconnect.verifiedComplete)
            assertTrue("Stored diagnostics result changed type after reconnect",
                afterReconnect.result is CompanionDiagnostics)
            assertTrue("Stored diagnostics start time changed", diagnostics.createdAt == afterReconnect.createdAt)
            assertTrue("Stored diagnostics finish time changed", diagnostics.finishedAt == afterReconnect.finishedAt)
            val afterProcesses = CompanionClient(model.client).getJob(processes.id)
            assertTrue("Stored processes result did not survive control reconnect", afterProcesses.verifiedComplete)
            assertTrue("Stored processes result changed type after reconnect", afterProcesses.result is CompanionProcesses)
        }
    }

    @Test fun transferQueueUploadsContentUrisAndVerifiesReadback() = safe {
        stage = "launch for AgentFiles transfers"
        launch()
        openAgent()
        val files = openFiles()
        activeFiles = files

        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        val payloads = listOf(
            ByteArray(196_611) { (it % 251).toByte() },
            ByteArray(65_537) { ((it * 7) % 256).toByte() },
        )
        val names = listOf(
            "android-ws-${UUID.randomUUID().toString().replace("-", "")}-a.bin",
            "android-ws-${UUID.randomUUID().toString().replace("-", "")}-b.bin",
        )
        val items = payloads.mapIndexed { index, payload ->
            val source = File.createTempFile("live-workspace-upload-", ".bin", updates)
                .apply { writeBytes(payload) }
            localFiles += source
            TransferItem(index.toLong() + 1, FileProvider.getUriForFile(
                context, "${context.packageName}.updates", source,
            ), names[index], payload.size.toLong())
        }
        val target = TransferTarget(
            profileId = workspace.profileId,
            profileName = profileName,
            nodeId = agentNodeId,
            nodeName = agentName,
            directory = FIXTURE_DIRECTORY,
            controller = files,
        )
        stage = "enqueue two confirmed content-URI uploads"
        // Track the owned names before the queue can start, so even an immediate failure is cleaned.
        pendingRemoteNames += names
        compose.runOnIdle {
            assertTrue("TransferQueue rejected the two-file batch", workspace.transfers.enqueueConfirmed(target, items))
        }
        stage = "await both verified uploads"
        val rows = runBlocking {
            withTimeout(120_000) {
                workspace.transfers.state.first { state ->
                    state.size == 2 && state.all { it.status != TransferStatus.Queued && it.status != TransferStatus.Active }
                }
            }
        }
        rows.forEachIndexed { index, row ->
            assertTrue("Upload $index did not succeed", row.status == TransferStatus.Succeeded)
            assertEquals("Upload $index byte count differs", payloads[index].size.toLong(), row.bytes)
            assertNull("Upload $index reported an error", row.error)
            assertFalse("Upload $index reported a partial remote", row.partialRemote)
        }

        stage = "independently download and compare both uploaded files"
        runBlocking {
            payloads.indices.forEach { index -> downloadAndCheck(files, names[index], payloads[index]) }
        }
        stage = "retry a failed upload through a fresh connection after transport closure"
        compose.runOnIdle {
            files.close()
            val retryItem = TransferItem(3L, items.first().uri, names.first(), payloads.first().size.toLong())
            assertTrue(workspace.transfers.enqueueConfirmed(target, listOf(retryItem)))
        }
        runBlocking { withTimeout(10_000) { workspace.transfers.state.first { it.any { row -> row.id == 3L && row.status == TransferStatus.Failed } } } }
        compose.runOnIdle { workspace.retryTransfer(3L) }
        runBlocking { withTimeout(60_000) { workspace.transfers.state.first { it.any { row -> row.id == 3L && row.status == TransferStatus.Succeeded } } } }
        val replacement = requireNotNull(workspace.transfers.target?.controller)
        assertNotSame("Retry must replace the closed transport", files, replacement)
        activeFiles = replacement
        runBlocking { downloadAndCheck(replacement, names.first(), payloads.first()) }
        // Best-effort removal of only this test's uniquely named fixture files. Cleanup retries.
        runCatching { runBlocking { deleteRemoteFiles(replacement, names) } }
    }

    private fun launch() {
        stage = "authenticate through the Application runtime"
        val state = workspace.client.clientState.value
        if (!state.socketConnected || state.requiresSignIn) {
            workspace.client.setAllowSelfSignedCertificates(true)
            assertTrue("Profile-scoped fixture credentials were not available",
                workspace.client.loginWithStoredCredentials(FIXTURE_SERVER))
        }
        runBlocking {
            withTimeout(60_000) {
                workspace.client.clientState.first { state ->
                    state.socketConnected && !state.requiresSignIn && state.devicesCurrent &&
                        state.devices.any { it.nodeId == agentNodeId } && state.devices.any { it.nodeId == sshNodeId }
                }
            }
        }
        stage = "launch MainActivity"
        scenario = ActivityScenario.launch(MainActivity::class.java)
        bindActivity()
        compose.waitUntil(20_000) { workspace.identity != null }
    }

    private fun bindActivity() {
        requireNotNull(scenario).onActivity {
            activity = it
            model = ViewModelProvider(it)[MeshSessionViewModel::class.java]
        }
    }

    private fun openAgent() {
        stage = "open the fixture agent card"
        compose.onNodeWithText(agentName).performScrollTo().performClick()
        compose.waitUntil(10_000) { model.selectedDeviceId == agentNodeId && model.deviceTab == DeviceTab.Overview }
    }

    /** Chromium below 80 cannot parse the bundled renderer's optional chaining. */
    private fun knownLegacyWebView(): Boolean {
        val version = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()?.versionName ?: return false
        val major = Regex("^(\\d+)(?:\\.|$)").find(version)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major < 80
    }

    private fun assertExpectedTerminalUnavailable(terminal: TerminalController) {
        assertEquals("Legacy WebView terminal did not report the required update message",
            UiText.resource(R.string.terminal_webview_update_required), terminal.state.value.message)
        assertEquals("Legacy WebView terminal did not enter the expected failed phase",
            RelayPhase.Failed, terminal.state.value.phase)
        assertFalse("Legacy WebView terminal unexpectedly became ready", terminal.state.value.ready)
    }

    private fun openTerminal(legacyWebView: Boolean): TerminalController {
        stage = "select the Terminal tab"
        tab(R.string.device_terminal)
        if (legacyWebView) {
            stage = "wait for the expected legacy WebView terminal failure"
            compose.waitUntil(25_000) {
                val state = model.terminalController?.state?.value
                state?.phase == RelayPhase.Failed &&
                    state.message == UiText.resource(R.string.terminal_webview_update_required)
            }
            val terminal = requireNotNull(model.terminalController)
            compose.runOnIdle { assertExpectedTerminalUnavailable(terminal) }
            compose.onNodeWithText(context.getString(R.string.terminal_webview_update_required)).assertIsDisplayed()
            return terminal
        }
        compose.waitUntil(40_000) { model.terminalController?.state?.value?.ready == true }
        return requireNotNull(model.terminalController)
    }

    private fun openFiles(): FilesController {
        stage = "select the Files tab"
        tab(R.string.device_files)
        compose.waitUntil(40_000) { model.filesController?.state?.value?.let { it.connected && !it.busy } == true }
        val files = requireNotNull(model.filesController)
        if (files.state.value.path != FIXTURE_DIRECTORY) {
            files.browse(FIXTURE_DIRECTORY)
            runBlocking {
                await(30_000) {
                    files.state.value.connected && !files.state.value.busy && files.state.value.path == FIXTURE_DIRECTORY
                }
            }
        }
        return files
    }

    private fun sessionId(controller: Any): String = workspace.sessions.single { it.terminal === controller || it.files === controller }.id

    private fun assertTwoSessions(terminal: TerminalController, files: FilesController, legacyWebView: Boolean) {
        compose.runOnIdle {
            assertTrue("Expected exactly two live slots", workspace.sessions.size == 2)
            assertTrue("Terminal controller is not the first slot", workspace.sessions.any { it.terminal === terminal })
            assertTrue("Files controller is not the second slot", workspace.sessions.any { it.files === files })
            if (legacyWebView) assertExpectedTerminalUnavailable(terminal)
            else assertTrue("Terminal transport is not ready", terminal.state.value.ready)
            assertTrue("Files transport is not connected", files.state.value.connected)
        }
    }

    private fun sendKnownHarmlessPrintf(controller: TerminalController) {
        stage = "select the preserved terminal and send a harmless printf"
        compose.runOnIdle {
            model.selectSession(workspace.sessions.single { it.terminal === controller }.id)
        }
        compose.waitUntil(10_000) { model.terminalController === controller }
        var renderer: TerminalWebRenderer? = null
        compose.runOnIdle { renderer = requireNotNull(controller.renderer(activity)) }
        val liveRenderer = requireNotNull(renderer)
        compose.waitUntil(20_000) { evaluateBoolean(liveRenderer, "typeof window.meshTerminal === 'object'") }
        val id = UUID.randomUUID().toString().replace("-", "")
        val marker = "MC_WS_$id"
        assertTrue("Terminal refused the harmless fixture command",
            controller.sendInput("printf 'MC_WS_%s\\n' '$id'\r"))
        compose.waitUntil(30_000) { evaluateBoolean(liveRenderer, markerOccursOnce(marker)) }
    }

    private suspend fun startAndAwait(companion: CompanionClient, template: CompanionTemplate): CompanionJob {
        val started = companion.startJob(
            nodeId = agentNodeId,
            template = template,
            parameters = emptyMap(),
            operationId = CompanionClient.newOperationId(),
            createdAt = System.currentTimeMillis(),
            confirmed = true,
        )
        return withTimeout(90_000) {
            var current = started
            while (!current.terminal) {
                delay(500)
                current = companion.getJob(started.id)
            }
            current
        }
    }

    private suspend fun awaitControlReady(client: MeshCentralClient) {
        withTimeout(60_000) {
            client.clientState.first { it.socketConnected && !it.requiresSignIn && it.devicesCurrent }
        }
    }

    private suspend fun downloadAndCheck(files: FilesController, name: String, expected: ByteArray) {
        await(45_000) { files.state.value.connected && !files.state.value.busy }
        // Clear a previous completion message before the new download, so the wait below cannot
        // observe the previous operation's success and read an empty destination file.
        if (files.state.value.message != null) {
            files.refresh()
            await(45_000) {
                files.state.value.connected && !files.state.value.busy && files.state.value.message == null
            }
        }
        val destination = File.createTempFile("live-workspace-readback-", ".bin", context.cacheDir)
        localFiles += destination
        files.download("$FIXTURE_DIRECTORY/$name", name, Uri.fromFile(destination))
        await(45_000) {
            files.state.value.connected && !files.state.value.busy &&
                files.state.value.message == UiText.resource(R.string.files_download_complete)
        }
        assertTrue("Read-back byte count differs", destination.length() == expected.size.toLong())
        assertTrue("Read-back SHA-256 differs",
            MessageDigest.isEqual(sha256(expected), sha256(destination.readBytes())))
    }

    private suspend fun deleteRemoteFiles(files: FilesController, names: List<String>) {
        if (!files.state.value.connected || files.state.value.busy) return
        for (name in names) {
            val entry = files.state.value.entries.firstOrNull { it.name == name } ?: continue
            files.propose(FileChange.Delete, entry)
            await(10_000) { files.state.value.confirmation != null }
            files.confirm(name)
            await(30_000) { !files.state.value.busy && files.state.value.entries.none { it.name == name } }
            pendingRemoteNames.remove(name)
        }
    }

    private fun tab(label: Int) {
        compose.onNode(hasText(context.getString(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performScrollTo().performClick()
    }

    private fun markerOccursOnce(marker: String): String =
        "(() => {window.meshTerminal.selectAll();const ok=window.meshTerminal.selection().split(${JSONObject.quote(marker)}).length===2;window.meshTerminal.clearSelection();return ok;})()"

    private fun evaluateBoolean(renderer: TerminalWebRenderer, script: String): Boolean {
        val complete = CountDownLatch(1)
        var answer = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            renderer.view.evaluateJavascript(script) { answer = it == "true"; complete.countDown() }
        }
        assertTrue("Fixture renderer JavaScript callback timed out", complete.await(5, TimeUnit.SECONDS))
        return answer
    }

    private suspend fun await(timeoutMillis: Long = 45_000, predicate: () -> Boolean) {
        withTimeout(timeoutMillis) { while (!predicate()) delay(20) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

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
            if (error is AssertionError || error !is Exception) throw error
            // Never attach a network exception cause: its message can contain a credentialed URL.
            val origin = error.stackTrace.take(4).joinToString(" <- ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            throw AssertionError("Live workspace failed at $stage (${error.javaClass.simpleName}; origin=$origin)")
        }
    }

    companion object {
        private const val FIXTURE_URL = "http://10.0.2.2:19857/fixture"
        private const val FIXTURE_SERVER = "https://10.0.2.2:19856"
        private const val FIXTURE_DIRECTORY = "/tmp/mesh-fixture"
    }
}
