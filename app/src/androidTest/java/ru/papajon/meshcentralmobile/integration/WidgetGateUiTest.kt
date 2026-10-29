package ru.papajon.meshcentralmobile.integration

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.notifications.NotificationStore
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.AppLockController
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.MeshCentralApp
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/**
 * The persistent widget rejection latch may be cleared only by the real [MeshCentralApp]
 * foreground authentication effect, never by a failed sign-in, a background success or a still
 * locked app. The composition is the production one; the server is a local MockWebServer.
 */
class WidgetGateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var settings: SettingsRepository
    private lateinit var vault: SecureVault
    private lateinit var appLock: AppLockController
    private lateinit var workspace: ConnectionWorkspace
    private lateinit var server: MockWebServer
    private lateinit var originalPreferences: Map<String, Map<String, *>>
    private lateinit var originalProfileId: String
    private var profileId: String? = null

    private val gate get() = context.getSharedPreferences(GATE_PREFERENCES, Context.MODE_PRIVATE)

    @Before
    fun captureAndPrepare() = runBlocking {
        settings = SettingsRepository(context)
        originalProfileId = settings.activeProfile.id
        originalPreferences = PREFERENCE_NAMES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        SecureVault(context).clearPin()
        settings.updateBiometricUnlock(false)
        settings.updateHideDevicesWhenLocked(false)
        context.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putLong("consent_version_code", 1L).commit()
        LauncherPrivacy.resetForTests()
        LauncherCoordinator.awaitIdle()
        LauncherCoordinator.clear(context)
        LauncherCoordinator.awaitIdle()

        server = MockWebServer()
        server.start()
        val profile = settings.createProfile(
            "Widget gate ${UUID.randomUUID()}",
            server.url("/").toString(),
            onboardingCompleted = true,
        )
        settings.activateProfile(profile.id)
        settings.updateAllowHttpInDebug(true)
        profileId = profile.id
        workspace = withContext(Dispatchers.Main) {
            ConnectionWorkspace.get(context.applicationContext as Application)
        }
        withTimeout(15_000) { while (workspace.clearingSession) delay(25) }
        withContext(Dispatchers.Main) {
            workspace.cancelPendingDestinations()
            workspace.message = null
            workspace.showChatsRequested = false
            workspace.showTransfersRequested = false
            workspace.switchProfile(profile.id)
        }
        vault = SecureVault(context)
        appLock = AppLockController(settings, vault)
        // This fixture hosts the app in ComponentActivity. Mirror MainActivity's window registry
        // so asynchronous PIN results have the same foreground guard as the production host.
        withContext(Dispatchers.Main) {
            val window = compose.activity
            window.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> appLock.onWindowStarted(window, false)
                    Lifecycle.Event.ON_STOP -> appLock.onWindowStopped(window)
                    else -> Unit
                }
            })
            if (window.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) appLock.onWindowStarted(window, false)
        }
    }

    @After
    fun restoreOriginalState() = runBlocking {
        runCatching { compose.activityRule.scenario.close() }
        if (::workspace.isInitialized) {
            runCatching { withTimeout(15_000) { while (workspace.clearingSession) delay(25) } }
            runCatching {
                withContext(Dispatchers.Main) {
                    workspace.client.logout(true)
                    workspace.switchProfile(originalProfileId)
                }
            }
        }
        profileId?.let { id ->
            runCatching { NotificationStore.clearProfile(context, id) }
            runCatching { SecureVault(context, id).clearCredentials() }
            runCatching { SecureVault(context, id).clearLoginKey() }
            runCatching { SettingsRepository(context).removeProfile(id) }
        }
        runCatching {
            LauncherCoordinator.awaitIdle()
            LauncherCoordinator.clear(context)
            LauncherCoordinator.awaitIdle()
        }
        if (::originalPreferences.isInitialized) {
            PREFERENCE_NAMES.forEach { name ->
                restorePreferences(name, originalPreferences.getValue(name))
            }
        }
        LauncherPrivacy.resetForTests()
        if (::server.isInitialized) server.close()
    }

    @Test
    fun rejectionLatchClearsOnlyAfterUnlockWithCurrentAuthenticatedForegroundState() = runBlocking {
        val id = requireNotNull(profileId)
        WidgetRefreshGate(context).reject(id)
        assertTrue("Fixture must start latched", gate.getBoolean("$id.blocked", false))

        compose.setContent {
            MeshCentralMobileTheme {
                MeshCentralApp(
                    settingsRepository = settings,
                    secureVault = vault,
                    appLockController = appLock,
                    onScreenshotBlockingChanged = {},
                    onBiometricUnlockRequested = { callback -> callback(false, null) },
                    onRemoteFullscreenChanged = {},
                )
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()

        // 1. A rejected password must not clear the persistent latch.
        server.enqueue(MockResponse().setResponseCode(401))
        withContext(Dispatchers.Main) {
            workspace.client.login(server.url("/").toString(), USER, "synthetic-wrong-password")
        }
        withTimeout(15_000) {
            workspace.client.clientState.first {
                it.requiresSignIn && it.connectionState == ConnectionState.Failed
            }
        }
        compose.waitForIdle()
        assertTrue("A failed sign-in must keep the widget latched", gate.getBoolean("$id.blocked", false))

        // 2. userinfo + nodes while the Activity is only CREATED must not clear it either.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertEquals(Lifecycle.State.CREATED, compose.activity.lifecycle.currentState)
        server.enqueue(MockResponse().withWebSocketUpgrade(successfulControlListener()))
        withContext(Dispatchers.Main) {
            workspace.client.login(server.url("/").toString(), USER, "synthetic-password")
        }
        withTimeout(15_000) {
            workspace.client.clientState.first {
                it.socketConnected && it.devicesCurrent && it.userId == USER && !it.requiresSignIn
            }
        }
        compose.waitForIdle()
        assertTrue(
            "A background authentication must not clear the foreground-only latch",
            gate.getBoolean("$id.blocked", false),
        )

        // 3. Returning to the foreground while locked must not clear it.
        vault.savePin(PIN)
        withContext(Dispatchers.Main) { appLock.lockNow() }
        assertTrue(appLock.isLocked)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(context.getString(R.string.lock_title)).assertIsDisplayed()
        compose.waitForIdle()
        assertTrue("A locked foreground must keep the latch", gate.getBoolean("$id.blocked", false))

        // 4. Only the unlocked production effect, with the current authenticated state, clears it.
        listOf("1", "3", "5", "7").forEach { compose.onNodeWithText(it).performClick() }
        compose.waitUntil(15_000) { !gate.getBoolean("$id.blocked", false) }
        compose.runOnIdle {
            assertFalse(appLock.isLocked)
            val state = workspace.client.clientState.value
            assertTrue(state.socketConnected)
            assertTrue(state.devicesCurrent)
            assertEquals(USER, state.userId)
            assertFalse(state.requiresSignIn)
        }
    }

    private fun successfulControlListener(): WebSocketListener = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                JSONObject().put("action", "userinfo")
                    .put("userinfo", JSONObject().put("_id", USER))
                    .toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val request = JSONObject(text)
            if (request.optString("action") == "nodes") {
                val reply = JSONObject().put("action", "nodes").put(
                    "nodes",
                    JSONObject().put(
                        "mesh//synthetic",
                        org.json.JSONArray().put(
                            JSONObject().put("_id", NODE)
                                .put("name", "Synthetic gate device")
                                .put("meshid", "mesh//synthetic")
                                .put("conn", 1),
                        ),
                    ),
                )
                if (request.has("responseid")) reply.put("responseid", request.get("responseid"))
                webSocket.send(reply.toString())
            }
        }
    }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, (value as Set<String>).toSet())
                }
            }
        }
        assertTrue("Could not restore $name", editor.commit())
    }

    private companion object {
        private const val GATE_PREFERENCES = "widget_auth_gate"
        private const val PIN = "1357"
        private const val USER = "user//widget-gate"
        private const val NODE = "node//widget-gate"
        private val PREFERENCE_NAMES = listOf(
            "app_settings",
            "secure_vault",
            "launcher_devices",
            "widget_auth_gate",
            "widget_status",
            "app_update_preferences",
            "notification_state",
        )
    }
}
