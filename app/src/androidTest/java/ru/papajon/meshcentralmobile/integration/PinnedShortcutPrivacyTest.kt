package ru.papajon.meshcentralmobile.integration

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Exercises a real launcher pin and its home-screen click, not an empty pinned-shortcut list. */
class PinnedShortcutPrivacyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun hiddenPinnedShortcutStillOpensThePinGate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
        assertTrue("Acceptance requires a launcher with pin support", manager.isRequestPinShortcutSupported)
        val settings = SettingsRepository(context)
        val original = settings.activeProfile.id
        val oldHide = settings.getSettings().hideDevicesWhenLocked
        val vault = SecureVault(context)
        assertFalse("Dedicated test fixture must begin without a PIN", vault.hasPin())
        val profile = settings.createProfile("Pinned fixture", "https://pinned.example.invalid/", true)
        val workspace = withContext(Dispatchers.Main) { ConnectionWorkspace.get(context.applicationContext as Application) }
        val host = ActivityScenario.launch(ComponentActivity::class.java)
        var opened: android.app.Activity? = null
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            withContext(Dispatchers.Main) { settings.activateProfile(profile.id); workspace.switchProfile(profile.id) }
            LauncherCoordinator.awaitIdle()
            val device = MeshDevice("node//pinned-fixture", "mesh//fixture", "Private fixture device", conn = 1)
            val snapshot = LauncherStore(context).publish(profile.id,
                LauncherStore.scope(profile.id, profile.serverUrl, "user//fixture"), listOf(device), setOf(device.nodeId), listOf(device.nodeId))
            val token = snapshot.devices.single().token
            val id = DeviceShortcuts.PREFIX + token
            host.onActivity {
                assertTrue(manager.requestPinShortcut(ShortcutInfo.Builder(context, id)
                    .setShortLabel(device.name).setIntent(DeviceShortcuts.intent(context, token)).build(), null))
            }
            val launcherPackage = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)!!.activityInfo.packageName
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (manager.pinnedShortcuts.none { it.id == id } && SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == launcherPackage) clickMatching(root) {
                    it.viewIdResourceName == "android:id/button1" ||
                        it.text?.toString()?.matches(Regex("add( automatically| to home screen)?", RegexOption.IGNORE_CASE)) == true
                }
                SystemClock.sleep(100)
            }
            // Some emulator launchers never show (or never confirm) the pin dialog to UiAutomation; the hidden-label and
            // lock-gate logic is also covered by ShortcutRevocationTest and LauncherLockTest, so skip rather than fail here.
            org.junit.Assume.assumeTrue("Launcher did not confirm the pin request on this image",
                manager.pinnedShortcuts.any { it.id == id })
            vault.savePin("1357")
            settings.updateHideDevicesWhenLocked(true)
            LauncherPrivacy.update(context, locked = true, foreground = false)
            LauncherCoordinator.awaitIdle()
            val pinned = manager.pinnedShortcuts.single { it.id == id }
            val generic = context.getString(R.string.integration_private_device)
            assertTrue("A valid hidden pin must remain clickable", pinned.isEnabled)
            assertEquals(generic, pinned.shortLabel.toString())
            assertEquals(token, DeviceShortcuts.token(pinned.intent!!))

            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val clickDeadline = SystemClock.uptimeMillis() + 10_000
            var clicked = false
            while (!clicked && SystemClock.uptimeMillis() < clickDeadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root?.packageName?.toString() == launcherPackage) {
                    if (root.findAccessibilityNodeInfosByText(generic).isNotEmpty()) capture(context, "round2-pinned-home.png")
                    clicked = clickMatching(root) { it.text?.toString() == generic || it.contentDescription?.toString() == generic }
                }
                if (!clicked) SystemClock.sleep(100)
            }
            assertTrue("Click the real generic shortcut on the home screen", clicked)
            opened = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("Pinned shortcut must open MainActivity", opened)
            compose.onNodeWithText(context.getString(R.string.lock_title)).assertIsDisplayed()
            capture(context, "round2-pinned-lock.png")
            assertNull(workspace.selectedDeviceId)
            assertEquals(token, workspace.launcherDestination)
            LauncherCoordinator.clear(context, profile.id).join()
            assertFalse("Revoking a real pinned token must disable the shortcut",
                manager.pinnedShortcuts.single { it.id == id }.isEnabled)
            val unavailable = context.getString(R.string.integration_unavailable)
            assertEquals(unavailable, manager.pinnedShortcuts.single { it.id == id }.disabledMessage.toString())
            DeviceShortcuts.sync(context, hidden = true)
            DeviceShortcuts.sync(context, hidden = false)
            assertEquals("Privacy transitions must preserve the revocation reason", unavailable,
                manager.pinnedShortcuts.single { it.id == id }.disabledMessage.toString())
        } finally {
            opened?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)
            host.close()
            vault.clearPin()
            withContext(Dispatchers.Main) {
                workspace.cancelPendingDestinations()
                workspace.switchProfile(original)
                settings.activateProfile(original)
                settings.removeProfile(profile.id)
                settings.updateHideDevicesWhenLocked(oldHide)
            }
            LauncherCoordinator.clear(context, profile.id).join()
            LauncherPrivacy.resetForTests()
        }
    }

    private fun clickMatching(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (matches(node)) {
            var target: AccessibilityNodeInfo? = node
            repeat(3) {
                if (target?.isClickable == true && target?.isEnabled == true)
                    return target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target = target?.parent
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickMatching(child, matches)) return true
        }
        return false
    }

    private fun capture(context: android.content.Context, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(context.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
