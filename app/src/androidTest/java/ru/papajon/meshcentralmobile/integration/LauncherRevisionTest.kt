package ru.papajon.meshcentralmobile.integration

import android.content.Context
import android.content.pm.ShortcutManager
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/**
 * Revision tests for issues 5, 6, 7 and 10: per-profile catalogs, stable opaque tokens,
 * serialized launcher IO and the locked/background privacy gate.
 *
 * Pinned-shortcut creation needs launcher user consent, which instrumentation cannot grant
 * without a UI confirmation. Public DTO/token/intent behavior is asserted directly; the hidden
 * invariant is also asserted for whichever pinned copies already exist on the device.
 */
class LauncherRevisionTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: LauncherStore get() = LauncherStore(context)
    private val profileA = "synthetic-launcher-a"
    private val profileB = "synthetic-launcher-b"
    private val devicesA = listOf(
        MeshDevice("node//synthetic-a1", "mesh//synthetic", "Synthetic alpha one", conn = 1),
        MeshDevice("node//synthetic-a2", "mesh//synthetic", "Synthetic alpha two", conn = 0),
    )
    private val devicesB = listOf(
        MeshDevice("node//synthetic-b1", "mesh//synthetic", "Synthetic beta one", conn = 1),
    )
    private val manager get() = context.getSystemService(ShortcutManager::class.java)!!
    private var hadWidgetStatus = false
    private var originalNeedsApp = false

    private fun scopeA(user: String = "user//synthetic-a") =
        LauncherStore.scope(profileA, "https://example.invalid/", user)

    private fun scopeB() = LauncherStore.scope(profileB, "https://example.invalid/", "user//synthetic-b")

    private fun tokens(vararg snapshots: LauncherSnapshot) = snapshots.flatMap { it.devices }.map { it.token }.toSet()

    @Before
    fun setUp() = runBlocking {
        val status = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE)
        hadWidgetStatus = status.contains("needs_app")
        originalNeedsApp = status.getBoolean("needs_app", false)
        LauncherPrivacy.resetForTests()
        setHidePolicy(false)
        SecureVault(context).clearPin()
        LauncherCoordinator.clear(context)
        LauncherCoordinator.awaitIdle()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        LauncherPrivacy.resetForTests()
        setHidePolicy(false)
        SecureVault(context).clearPin()
        LauncherCoordinator.clear(context)
        LauncherCoordinator.awaitIdle()
        val editor = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE).edit()
        if (hadWidgetStatus) editor.putBoolean("needs_app", originalNeedsApp) else editor.remove("needs_app")
        editor.commit()
    }

    private fun setHidePolicy(enabled: Boolean) {
        SettingsRepository(context).updateHideDevicesWhenLocked(enabled)
    }

    @Test
    fun aToBToATokensStayStableAndBothProfilesRemainCached() {
        val firstA = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        val firstB = store.publish(profileB, scopeB(), devicesB, devicesB.map { it.nodeId }.toSet(),
            devicesB.map { it.nodeId }, 2_000L)
        store.activate(profileA)
        val secondA = store.read()!!
        val secondB = store.all().first { it.profileId == profileB }

        assertEquals(firstA.generation, secondA.generation)
        assertEquals(tokens(firstA), tokens(secondA))
        assertEquals(tokens(firstB), tokens(secondB))
        assertTrue(tokens(firstA).intersect(tokens(firstB)).isEmpty())
        assertEquals(1_000L, secondA.updatedAt)
        assertEquals(profileA, store.find(firstA.devices.first().token)?.profileId)
        assertEquals(profileB, store.find(firstB.devices.first().token)?.profileId)
        // The shortcut intent still exposes only the same opaque token after the round trip.
        val token = secondA.devices.first().token
        assertEquals(token, DeviceShortcuts.token(DeviceShortcuts.intent(context, token)))
    }

    @Test
    fun clearingOneProfileKeepsTheOtherProfileAndItsShortcuts() = runBlocking {
        val a = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        val b = store.publish(profileB, scopeB(), devicesB, devicesB.map { it.nodeId }.toSet(),
            devicesB.map { it.nodeId }, 2_000L)
        val aToken = a.devices.first().token
        val bToken = b.devices.first().token

        LauncherCoordinator.clear(context, profileA)
        LauncherCoordinator.awaitIdle()

        assertNull(store.find(aToken))
        assertNotNull(store.find(bToken))
        assertEquals(listOf(profileB), store.all().map { it.profileId })
        assertEquals(profileB, store.read()?.profileId)
        val dynamicTokens = manager.dynamicShortcuts.filter { it.id.startsWith(DeviceShortcuts.PREFIX) }
            .mapNotNull { DeviceShortcuts.token(it.intent ?: android.content.Intent()) }
        assertEquals(setOf(bToken), dynamicTokens.toSet())
    }

    @Test
    fun scopeChangeRotatesTokensAndRevokesTheOldGeneration() {
        val old = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        DeviceShortcuts.sync(context, old, hidden = false)
        val oldTokens = tokens(old)
        val current = store.publish(profileA, scopeA("user//other"), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 2_000L)

        assertNotEquals(old.generation, current.generation)
        assertTrue(oldTokens.intersect(tokens(current)).isEmpty())
        oldTokens.forEach { assertNull(store.find(it)) }
        assertFalse(store.refresh(old, devicesA))
        DeviceShortcuts.sync(context, current, hidden = false)
        val dynamicTokens = manager.dynamicShortcuts.filter { it.id.startsWith(DeviceShortcuts.PREFIX) }
            .mapNotNull { DeviceShortcuts.token(it.intent ?: android.content.Intent()) }
        assertEquals(tokens(current), dynamicTokens.toSet())
        assertTrue(dynamicTokens.none { it in oldTokens })
    }

    @Test
    fun identicalPublishDoesNotWriteANewSnapshotOrUpdatedAt() {
        val first = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        val prefs = context.getSharedPreferences("launcher_devices", Context.MODE_PRIVATE)
        val before = prefs.getString("snapshot.$profileA", null)
        val second = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 99_000L)
        val after = prefs.getString("snapshot.$profileA", null)

        assertEquals(first, second)
        assertEquals(1_000L, second.updatedAt)
        assertEquals(before, after)
    }

    @Test
    fun coordinatorChecksCanPublishBeforeTouchingTheStore() = runBlocking {
        LauncherCoordinator.publish(context, profileA, scopeA(), devicesA,
            devicesA.map { it.nodeId }.toSet(), devicesA.map { it.nodeId }, 1_000L,
            canPublish = { false })
        LauncherCoordinator.awaitIdle()
        assertNull(store.all().firstOrNull { it.profileId == profileA })
    }

    @Test
    fun coordinatorRefreshKeepsTheGenerationGuardBeforeRendering() = runBlocking {
        store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        val ticket = requireNotNull(LauncherCoordinator.beginRefresh(context))
        val current = store.publish(profileA, scopeA("user//other"), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 2_000L)
        assertFalse(LauncherCoordinator.refresh(context, ticket, devicesA))
        LauncherCoordinator.awaitIdle()
        assertEquals(current.generation, store.read()!!.generation)
        assertEquals(2_000L, store.read()!!.updatedAt)
    }

    @Test fun staleFailureCannotReplaceAnotherProfileOrAnUnchangedSuccessfulPublication() = runBlocking {
        LauncherCoordinator.publish(context, profileA, scopeA(), devicesA, emptySet(), emptyList(), 1_000).join()
        val oldA = requireNotNull(LauncherCoordinator.beginRefresh(context))
        LauncherCoordinator.publish(context, profileB, scopeB(), devicesB, emptySet(), emptyList(), 2_000).join()
        LauncherCoordinator.reportRefreshFailure(context, oldA).join()
        assertFalse(context.getSharedPreferences("widget_status", Context.MODE_PRIVATE).getBoolean("needs_app", true))
        assertEquals(profileB, store.read()!!.profileId)
        val oldB = requireNotNull(LauncherCoordinator.beginRefresh(context))
        // No projection change, but it is still a fresh successful server observation.
        LauncherCoordinator.publish(context, profileB, scopeB(), devicesB, emptySet(), emptyList(), 3_000).join()
        assertEquals(oldB.snapshot, store.read())
        LauncherCoordinator.reportRefreshFailure(context, oldB).join()
        assertFalse(context.getSharedPreferences("widget_status", Context.MODE_PRIVATE).getBoolean("needs_app", true))
    }

    @Test fun lateFailureCannotReplaceASuccessfulRefresh() = runBlocking {
        LauncherCoordinator.publish(context, profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(), emptyList(), 1_000).join()
        val ticket = requireNotNull(LauncherCoordinator.beginRefresh(context))
        assertTrue(LauncherCoordinator.refresh(context, ticket, devicesA.map { it.copy(conn = 0) }))
        LauncherCoordinator.reportRefreshFailure(context, ticket).join()
        assertFalse(context.getSharedPreferences("widget_status", Context.MODE_PRIVATE).getBoolean("needs_app", true))
        assertTrue(store.read()!!.devices.none { it.online })
    }

    @Test fun timedOutQueuedRefreshCannotPublishAfterItsCallerIsCancelled() = runBlocking {
        LauncherCoordinator.publish(context, profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(), emptyList(), 1_000).join()
        val ticket = requireNotNull(LauncherCoordinator.beginRefresh(context))
        val status = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE)
        status.edit().putBoolean("needs_app", true).commit()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = LauncherCoordinator.publish(context, profileA, scopeA(), devicesA, emptySet(), emptyList(),
            canPublish = { entered.countDown(); release.await(15, java.util.concurrent.TimeUnit.SECONDS); false })
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            try {
                kotlinx.coroutines.withTimeout(100) { LauncherCoordinator.refresh(context, ticket, emptyList()) }
                org.junit.Assert.fail("The queued caller must time out")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        } finally { release.countDown() }
        blocker.join()
        LauncherCoordinator.awaitIdle()
        assertEquals(ticket.snapshot, store.read())
        assertTrue(status.getBoolean("needs_app", false))
    }

    @Test
    fun lockedPrivacyHidesNamesStatusTimeAndShortcutsAndUnlockRestoresThem() = runBlocking {
        SecureVault(context).savePin("1357")
        setHidePolicy(true)
        LauncherPrivacy.update(context, locked = false, foreground = true)
        LauncherCoordinator.awaitIdle()
        val snapshot = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        DeviceShortcuts.sync(context, snapshot, hidden = false)
        assertTrue(manager.dynamicShortcuts.any { it.id.startsWith(DeviceShortcuts.PREFIX) })

        LauncherPrivacy.update(context, locked = true, foreground = false)
        assertTrue(LauncherPrivacy.hidden(context))
        LauncherCoordinator.awaitIdle()

        assertTrue(manager.dynamicShortcuts.none { it.id.startsWith(DeviceShortcuts.PREFIX) })
        // Pinned copies may exist only when a launcher previously accepted a pin request. A valid
        // retained token stays reachable behind the generic label; a revoked one stays disabled.
        val retained = store.all().flatMap { it.devices }.map { it.token }.toSet()
        manager.pinnedShortcuts.filter { it.id.startsWith(DeviceShortcuts.PREFIX) }.forEach {
            assertEquals(context.getString(R.string.integration_private_device), it.shortLabel?.toString())
            val token = DeviceShortcuts.token(it.intent ?: android.content.Intent())
            if (token != null && token in retained) assertTrue(it.isEnabled)
            else assertFalse(it.isEnabled)
        }

        val widget = render(FavoritesWidget.views(context, 901, snapshot, hidden = true))
        assertEquals(context.getString(R.string.integration_private_hidden), text(widget, R.id.widget_updated))
        assertEquals("", text(widget, R.id.widget_hint))
        assertEquals(context.getString(R.string.integration_refresh), text(widget, R.id.widget_refresh))
        assertFalse(text(widget, R.id.widget_updated).contains(devicesA.first().name))
        val hiddenRow = render(FavoritesWidget.row(context, snapshot.devices.first(), hidden = true))
        assertEquals(context.getString(R.string.integration_private_device), text(hiddenRow, R.id.widget_device_name))
        assertEquals("", text(hiddenRow, R.id.widget_device_status))

        LauncherPrivacy.update(context, locked = false, foreground = true)
        assertFalse(LauncherPrivacy.hidden(context))
        LauncherCoordinator.awaitIdle()
        val restored = manager.dynamicShortcuts.filter { it.id.startsWith(DeviceShortcuts.PREFIX) }
        assertEquals(devicesA.map { it.name }.toSet(), restored.mapNotNull { it.shortLabel?.toString() }.toSet())
        val restoredRow = render(FavoritesWidget.row(context, snapshot.devices.first(), hidden = false))
        assertEquals(devicesA.first().name, text(restoredRow, R.id.widget_device_name))
        assertEquals(context.getString(R.string.common_status_online), text(restoredRow, R.id.widget_device_status))
    }

    @Test
    fun backgroundHidesImmediatelyWithoutChangingThePin() = runBlocking {
        SecureVault(context).savePin("1357")
        setHidePolicy(true)
        LauncherPrivacy.update(context, locked = false, foreground = true)
        LauncherCoordinator.awaitIdle()
        val snapshot = store.publish(profileA, scopeA(), devicesA, devicesA.map { it.nodeId }.toSet(),
            devicesA.map { it.nodeId }, 1_000L)
        DeviceShortcuts.sync(context, snapshot, hidden = false)

        LauncherPrivacy.update(context, locked = false, foreground = true)
        assertFalse(LauncherPrivacy.hidden(context))
        LauncherCoordinator.awaitIdle()
        val unlockedTokens = manager.dynamicShortcuts.map { it.id }.toSet()
        assertTrue(unlockedTokens.any { it.startsWith(DeviceShortcuts.PREFIX) })

        LauncherPrivacy.update(context, locked = false, foreground = false)
        assertTrue(LauncherPrivacy.hidden(context))
        LauncherCoordinator.awaitIdle()
        assertTrue(manager.dynamicShortcuts.none { it.id.startsWith(DeviceShortcuts.PREFIX) })
        assertTrue(SecureVault(context).verifyPin("1357"))
    }

    private fun render(views: android.widget.RemoteViews): View = views.apply(context, FrameLayout(context))

    private fun text(view: View, id: Int): String = view.findViewById<TextView>(id).text.toString()
}
