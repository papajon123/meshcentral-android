package ru.papajon.meshcentralmobile.integration

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LauncherLockTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun shortcutAndWidgetDestinationWaitForPinThenOpenAuthorizedOverviewAndRefreshOnce() = runBlocking {
        val node = "node//synthetic-launch"
        val user = "user//synthetic-launch"
        val requests = AtomicInteger()
        val socket = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        var online = true
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.set(webSocket)
                webSocket.send(JSONObject().put("action", "userinfo").put("userinfo", JSONObject().put("_id", user)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text)
                if (request.optString("action") == "nodes") {
                    requests.incrementAndGet()
                    val reply = JSONObject().put("action", "nodes").put("nodes", JSONObject().put("mesh//synthetic",
                        org.json.JSONArray().put(JSONObject().put("_id", node).put("name", "Synthetic workstation")
                            .put("meshid", "mesh//synthetic").put("conn", if (online) 1 else 0))))
                    if (request.has("responseid")) reply.put("responseid", request.get("responseid"))
                    webSocket.send(reply.toString())
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val settings = SettingsRepository(context)
        val original = settings.activeProfile
        val profile = settings.createProfile("Synthetic launcher", server.url("/").toString(), true)
        val vault = SecureVault(context)
        val workspace = withContext(Dispatchers.Main) { ConnectionWorkspace.get(context.applicationContext as Application) }
        var activity: ActivityScenario<MainActivity>? = null
        var testFailure: Throwable? = null
        try {
            withContext(Dispatchers.Main) {
                settings.activateProfile(profile.id)
                settings.updateAllowHttpInDebug(true)
                settings.addFavorite("Synthetic workstation", node)
                workspace.switchProfile(profile.id)
                workspace.client.login(profile.serverUrl, "synthetic-user", "synthetic-password")
            }
            withTimeout(15_000) { workspace.client.clientState.first { it.devicesCurrent && it.userId == user } }
            val snapshot = LauncherStore(context).publish(profile.id, LauncherStore.scope(profile.id, profile.serverUrl, user),
                workspace.client.clientState.value.devices, setOf(node), listOf(node))
            vault.savePin("1357")
            // The lock is process-wide and already exists; a cold start with a PIN begins locked.
            withContext(Dispatchers.Main) { workspace.appLock.lockNow() }
            activity = ActivityScenario.launch(DeviceShortcuts.intent(context, snapshot.devices.single().token))
            compose.onNodeWithText(context.getString(R.string.lock_title)).assertIsDisplayed()
            compose.runOnIdle {
                assertNull(workspace.selectedDeviceId)
                assertEquals(snapshot.devices.single().token, workspace.launcherDestination)
            }
            capture("integration-lock.png")
            listOf("1", "3", "5", "7").forEach { compose.onNodeWithText(it).performClick() }
            compose.waitUntil(10_000) { workspace.selectedDeviceId == node }
            compose.runOnIdle { assertNull(workspace.launcherDestination) }
            activity.onActivity { opened ->
                val saved = android.os.Bundle()
                instrumentation.callActivityOnSaveInstanceState(opened, saved)
                assertTrue("Chat delivery must survive integration", saved.containsKey("handledChatDelivery"))
                assertTrue("Pending launcher destination must also be saved", saved.containsKey("pendingLauncherToken"))
            }
            // A notification/shortcut delivered to an already resumed Activity must not
            // treat an old onStop timestamp as a fresh foreground transition.
            settings.saveLastBackgroundAt(System.currentTimeMillis() - 600_000)
            activity.onActivity { opened ->
                val trackedIntent = opened.intent
                instrumentation.callActivityOnNewIntent(opened, DeviceShortcuts.intent(context, snapshot.devices.single().token))
                // ActivityScenario filters lifecycle events by its launch URI. Restore that
                // harness identity after the real callback has captured the opaque token.
                opened.intent = trackedIntent
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.lock_title)).assertDoesNotExist()
            compose.runOnIdle { workspace.browseDevices() }
            val forged = java.util.UUID.randomUUID().toString()
            activity.onActivity { opened ->
                val trackedIntent = opened.intent
                instrumentation.callActivityOnNewIntent(opened, DeviceShortcuts.intent(context, forged))
                opened.intent = trackedIntent
            }
            compose.waitUntil(10_000) { workspace.launcherDestination == null }
            compose.runOnIdle {
                assertNull(workspace.selectedDeviceId)
                assertEquals(ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.integration_unavailable), workspace.message)
            }
            val foreign = LauncherStore(context).publish(profile.id, LauncherStore.scope(profile.id, profile.serverUrl, "user//foreign"),
                workspace.client.clientState.value.devices, setOf(node), listOf(node))
            activity.onActivity { opened ->
                val trackedIntent = opened.intent
                instrumentation.callActivityOnNewIntent(opened, DeviceShortcuts.intent(context, foreign.devices.single().token))
                opened.intent = trackedIntent
            }
            compose.waitUntil(10_000) { workspace.launcherDestination == null }
            compose.runOnIdle {
                assertNull(workspace.selectedDeviceId)
                assertEquals(ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.integration_unavailable), workspace.message)
            }
            LauncherStore(context).publish(profile.id, LauncherStore.scope(profile.id, profile.serverUrl, user),
                workspace.client.clientState.value.devices, setOf(node), listOf(node))
            online = false
            val before = requests.get()
            assertTrue(WidgetRefreshWorker.refresh(context))
            assertEquals(before + 1, requests.get())
            assertFalse(LauncherStore(context).read()!!.devices.single().online)
            compose.runOnIdle { workspace.browseDevices() }
            activity.recreate()
            compose.waitForIdle()
            compose.runOnIdle { assertNull("Rotation must not replay a consumed destination", workspace.selectedDeviceId) }
            // Logout clears launcher data before slow best-effort remote cleanup completes.
            withContext(Dispatchers.Main) { workspace.clearSavedSession { } }
            compose.waitUntil(10_000) { !workspace.clearingSession }
            assertNull(LauncherStore(context).read())
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            val closeError = runCatching { activity?.close() }.exceptionOrNull()
            vault.clearPin()
            withContext(Dispatchers.Main) {
                workspace.appLock.refreshLockRequirement()
                workspace.client.logout(true)
                workspace.switchProfile(original.id)
                settings.activateProfile(original.id)
                settings.removeProfile(profile.id)
                DeviceShortcuts.clear(context)
            }
            socket.get()?.close(1000, "Fixture complete")
            server.close()
            if (closeError != null) {
                if (testFailure != null) testFailure.addSuppressed(closeError) else throw closeError
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun coldRefreshClosesItsTemporarySocketAndNeverPersistsLateCredentials() = runBlocking {
        val user = "user//synthetic-cold"
        val node = "node//synthetic-cold"
        val closed = java.util.concurrent.CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("action", "userinfo").put("userinfo", JSONObject().put("_id", user)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("action") == "nodes") webSocket.send(JSONObject().put("action", "nodes")
                    .put("nodes", JSONObject().put("mesh//synthetic", org.json.JSONArray().put(JSONObject()
                        .put("_id", node).put("name", "Cold synthetic").put("conn", 1)))).toString())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); closed.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { closed.countDown() }
        }))
        server.start()
        val settings = SettingsRepository(context)
        val original = settings.activeProfile
        val profile = settings.createProfile("Cold fixture", server.url("/").toString(), true)
        val writable = SecureVault(context, profile.id)
        try {
            settings.activateProfile(profile.id)
            settings.updateAllowHttpInDebug(true)
            writable.saveCredentials("synthetic-user", "synthetic-password", profile.serverUrl)
            val before = writable.getCredentials(profile.serverUrl)
            val store = LauncherStore(context)
            store.publish(profile.id, LauncherStore.scope(profile.id, profile.serverUrl, user),
                listOf(ru.papajon.meshcentralmobile.mesh.MeshDevice(node, "mesh//synthetic", "Cold synthetic", conn = 0)), setOf(node), emptyList())
            assertTrue(withTimeout(12_000) { WidgetRefreshWorker.refresh(context) })
            assertTrue(store.read()!!.devices.single().online)
            assertTrue("Temporary socket must close", closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
            assertEquals(before, writable.getCredentials(profile.serverUrl))
        } finally {
            DeviceShortcuts.clear(context)
            writable.clearCredentials()
            settings.activateProfile(original.id)
            settings.removeProfile(profile.id)
            server.close()
        }
    }
}
