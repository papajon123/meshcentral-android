package ru.papajon.meshcentralmobile.integration

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

class LauncherIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val devices = (0..5).map { MeshDevice("node//synthetic-$it", "mesh//example", "Synthetic device $it", conn = it % 2) }
    private val profile = "synthetic-launcher"
    private val scope get() = LauncherStore.scope(profile, "https://example.invalid/", "user//synthetic")
    private fun publish() = LauncherStore(context).publish(profile, scope, devices, devices.map { it.nodeId }.toSet(), devices.map { it.nodeId }, 1_700_000_000_000L)
    @After fun cleanup() { compose.runOnIdle { DeviceShortcuts.clear(context) } }

    @Test fun shortcutsPublishFourRecentsWithOpaqueIntentsAndRevokeOnLogout() {
        compose.runOnIdle {
            DeviceShortcuts.clear(context)
            val snapshot = publish()
            DeviceShortcuts.sync(context, snapshot)
            val manager = context.getSystemService(ShortcutManager::class.java)
            val shortcuts = manager.dynamicShortcuts
            assertEquals(minOf(4, manager.maxShortcutCountPerActivity), shortcuts.size)
            shortcuts.sortedBy { it.rank }.forEachIndexed { index, shortcut ->
                assertEquals(devices[index].name, shortcut.shortLabel)
                val intent = shortcut.intent!!
                assertNotNull(DeviceShortcuts.token(intent))
                assertFalse(intent.toUri(0).contains("example.invalid"))
                assertFalse(intent.toUri(0).contains("node//"))
                assertFalse(intent.toUri(0).contains("user//"))
            }
            DeviceShortcuts.clear(context, profile)
            assertTrue(manager.dynamicShortcuts.isEmpty())
            assertNull(LauncherStore(context).read())
            assertFalse(LauncherStore(context).refresh(snapshot, devices))
        }
    }

    @Test fun profileDeletionRevokesEntriesAndLateRefreshCannotResurrectThem() = runBlocking {
        val preferences = "launcher-delete-test"
        val settings = SettingsRepository(context, preferences)
        val added = settings.createProfile("Synthetic", "https://example.invalid/")
        val before = LauncherStore(context).publish(added.id, LauncherStore.scope(added.id, added.serverUrl, "user//x"), devices, emptySet(), devices.map { it.nodeId })
        compose.runOnIdle { DeviceShortcuts.sync(context, before) }
        try {
            assertTrue(settings.removeProfile(added.id))
            // removeProfile enqueues the launcher cleanup on LauncherCoordinator's IO queue.
            // Wait for it off the main thread; runOnIdle would block Main while the queue needs it.
            LauncherCoordinator.awaitIdle()
            assertNull(LauncherStore(context).read())
            assertFalse(LauncherStore(context).refresh(before, devices))
        } finally {
            context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
            // The isolated settings never owned the app profile; point the launcher back at it.
            LauncherStore(context).activate(SettingsRepository(context).activeProfile.id)
        }
    }

    @Test fun accountChangeRotatesOpaqueTokensAndInvalidatesOldRefresh() {
        val before = publish()
        val after = LauncherStore(context).publish(profile, LauncherStore.scope(profile, "https://example.invalid/", "user//other"), devices, emptySet(), devices.map { it.nodeId })
        assertNotEquals(before.generation, after.generation)
        assertTrue(before.devices.map { it.token }.intersect(after.devices.map { it.token }.toSet()).isEmpty())
        assertFalse(LauncherStore(context).refresh(before, devices))
        assertNull(DeviceShortcuts.token(Intent(DeviceShortcuts.ACTION_OPEN).setData(android.net.Uri.parse("mesh-device://open/not-a-token"))))
    }

    @Test fun remoteViewsRenderFavoriteNameStatusAndTimeWithoutServerData() {
        compose.runOnIdle {
            val snapshot = publish()
            val root = FavoritesWidget.views(context, 123, snapshot).apply(context, FrameLayout(context))
            assertEquals(context.getString(R.string.integration_favorites), root.findViewById<TextView>(R.id.widget_title).text)
            assertEquals(context.getString(R.string.integration_updated,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(snapshot.updatedAt))), root.findViewById<TextView>(R.id.widget_updated).text)
            val row = FavoritesWidget.row(context, snapshot.devices.first()).apply(context, FrameLayout(context))
            assertEquals(devices[0].name, row.findViewById<TextView>(R.id.widget_device_name).text)
            assertEquals(context.getString(R.string.common_status_offline), row.findViewById<TextView>(R.id.widget_device_status).text)
            assertTrue(LauncherStore(context).refresh(snapshot, devices.map { it.copy(conn = 1) }))
            FavoritesWidget.row(context, LauncherStore(context).read()!!.devices.first()).reapply(context, row)
            assertEquals(context.getString(R.string.common_status_online), row.findViewById<TextView>(R.id.widget_device_status).text)
            DeviceShortcuts.clear(context)
            FavoritesWidget.views(context, 123).reapply(context, root)
            assertEquals(context.getString(R.string.integration_widget_empty), root.findViewById<TextView>(R.id.widget_updated).text)
        }
    }

    @Test fun refreshWithFilledLauncherCacheButNoCredentialsDoesNotCreateAConnection() = runBlocking {
        val server = MockWebServer()
        server.start()
        val settings = SettingsRepository(context)
        val original = settings.activeProfile
        val profile = settings.createProfile("Synthetic empty cache", server.url("/").toString(), true)
        try {
            val user = "user//synthetic-empty-cache"
            val nodeIds = devices.map { it.nodeId }
            LauncherStore(context).publish(profile.id, LauncherStore.scope(profile.id, profile.serverUrl, user),
                devices, nodeIds.toSet(), nodeIds)
            settings.activateProfile(profile.id)
            settings.updateAllowHttpInDebug(true)
            LauncherCoordinator.awaitIdle()
            assertNotNull("The launcher cache must be filled before the refresh", LauncherStore(context).read())
            assertNull("No credentials are stored for this profile", SecureVault(context, profile.id).getCredentials(profile.serverUrl))
            val before = server.requestCount
            assertFalse(WidgetRefreshWorker.refresh(context))
            assertEquals("A cache without credentials must not reach localhost", before, server.requestCount)
        } finally {
            LauncherStore(context).clear(profile.id)
            settings.activateProfile(original.id)
            settings.removeProfile(profile.id)
            LauncherCoordinator.awaitIdle()
            server.close()
        }
    }

    @Test fun widgetHostReceivesRealCollectionUpdatesAndClearsAfterLogout() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val host = android.appwidget.AppWidgetHost(context, 5676)
        lateinit var view: android.appwidget.AppWidgetHostView
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        var id = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        try {
            // Shell permission adoption needs Android 10; older releases grant widget binding by package.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            } else {
                automation.executeShellCommand("appwidget grantbind --package ${context.packageName}").close()
            }
            compose.runOnIdle {
                id = host.allocateAppWidgetId()
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, android.content.ComponentName(context, FavoritesWidget::class.java)))
                host.startListening()
                view = host.createView(compose.activity, id, manager.getAppWidgetInfo(id))
                val density = context.resources.displayMetrics.density
                val container = FrameLayout(compose.activity).apply {
                    setBackgroundColor(android.graphics.Color.DKGRAY)
                    addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (400 * density).toInt()).apply {
                        topMargin = (56 * density).toInt()
                        leftMargin = (12 * density).toInt()
                        rightMargin = (12 * density).toInt()
                    })
                }
                compose.activity.setContentView(container)
                publish()
                FavoritesWidget.updateAll(context)
            }
            compose.waitUntil(10_000) { view.findViewById<android.widget.ListView>(R.id.widget_rows)?.count == 6 }
            compose.runOnIdle {
                assertEquals(devices[0].name, view.findViewById<android.widget.ListView>(R.id.widget_rows).getChildAt(0).findViewById<TextView>(R.id.widget_device_name).text)
            }
            val screenshot = automation.takeScreenshot() ?: error("No widget screenshot")
            java.io.File(context.filesDir, "integration-widget.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            // Exercise the actual collection PendingIntent, not just a hand-built equivalent.
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(ru.papajon.meshcentralmobile.MainActivity::class.java.name, null, false)
            try {
                val token = LauncherStore(context).read()!!.devices.first().token
                compose.runOnIdle {
                    val list = view.findViewById<android.widget.ListView>(R.id.widget_rows)
                    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("Widget row must launch MainActivity", opened)
                assertEquals(token, DeviceShortcuts.token(opened.intent))
                instrumentation.runOnMainSync { opened.finish() }
            } finally { instrumentation.removeMonitor(monitor) }
            compose.runOnIdle { DeviceShortcuts.clear(context) }
            compose.waitUntil(10_000) { view.findViewById<android.widget.ListView>(R.id.widget_rows)?.count == 0 }
        } finally {
            compose.runOnIdle { host.stopListening(); if (id != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                automation.dropShellPermissionIdentity()
            } else {
                automation.executeShellCommand("appwidget revokebind --package ${context.packageName}").close()
            }
        }
    }
}
