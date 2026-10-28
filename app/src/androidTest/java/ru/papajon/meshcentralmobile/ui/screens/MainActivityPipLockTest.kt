package ru.papajon.meshcentralmobile.ui.screens

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.AppLockController
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipHost
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Runs the production Activity and its real platform PiP callbacks, including resize recreation. */
class MainActivityPipLockTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Volatile private var current: MainActivity? = null
    private val activity get() = current ?: compose.activity
    private val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: android.app.Activity, state: android.os.Bundle?) { if (a is MainActivity) current = a }
        override fun onActivityResumed(a: android.app.Activity) { if (a is MainActivity) current = a }
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityPaused(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, state: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    }
    @Before fun trackRecreation() {
        current = compose.activity
        activity.application.registerActivityLifecycleCallbacks(callbacks)
        compose.runOnUiThread {
            SecureVault(context).clearPin()
            controller().refreshLockRequirement()
            settings().saveLastBackgroundAt(0)
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun controller(): AppLockController = MainActivity::class.java.getDeclaredField("appLockController")
        .apply { isAccessible = true }.get(activity) as AppLockController
    private fun host(): DesktopPipHost = MainActivity::class.java.getDeclaredMethod("getDesktopPip")
        .apply { isAccessible = true }.invoke(activity) as DesktopPipHost
    private fun settings(): SettingsRepository = MainActivity::class.java.getDeclaredField("settingsRepository")
        .apply { isAccessible = true }.get(activity) as SettingsRepository
    private fun suppressed(): Boolean {
        val client = ConnectionWorkspace.get(activity.application).client
        val relay = client.javaClass.getDeclaredField("relay").apply { isAccessible = true }.get(client)
        return relay.javaClass.getDeclaredField("inputSuppressed").apply { isAccessible = true }.getBoolean(relay)
    }

    @After fun cleanup() {
        activity.application.unregisterActivityLifecycleCallbacks(callbacks)
        instrumentation.runOnMainSync {
            SecureVault(context).clearPin()
            settings().updateLockTimeoutSeconds(60)
            controller().refreshLockRequirement()
            settings().saveLastBackgroundAt(0)
        }
    }

    @Test fun expiredPipRequiresPinBeforeRelayInputCanResume() = exercise(expired = true)
    @Test fun shortPipReturnRetainsUnlockedSessionAcrossResize() = exercise(expired = false)

    private fun exercise(expired: Boolean) {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(60)
            assertTrue(controller().unlockWithPin("1234"))
        }
        compose.waitForIdle()
        SystemClock.sleep(500)
        val originalController = controller()
        val enteredAfter = System.currentTimeMillis()
        compose.runOnUiThread {
            host().configure(true, false, 640, 480)
            assertTrue(host().enter())
        }
        awaitPip()
        compose.mainClock.advanceTimeBy(1000)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1000)
        val firstDeadlineStart = settings().getLastBackgroundAt()
        val beforeRecreation = activity
        instrumentation.runOnMainSync { activity.recreate() }
        compose.waitUntil(10000) { activity !== beforeRecreation && activity.isInPictureInPictureMode }
        instrumentation.runOnMainSync {
            assertEquals("PiP recreation must not restart the original deadline", firstDeadlineStart, settings().getLastBackgroundAt())
            assertTrue("PiP gate: platform=${activity.isInPictureInPictureMode} host=${host().active} locked=${controller().isLocked}", suppressed())
            assertSame("configuration recreation must retain the lock controller", originalController, controller())
            assertFalse("A short PiP must retain unlock, including inside the resized Activity", controller().isLocked)
            assertTrue("PiP must create a fresh timestamp, not inherit the preceding test",
                settings().getLastBackgroundAt() in enteredAfter..System.currentTimeMillis())
            if (expired) settings().saveLastBackgroundAt(System.currentTimeMillis() - 61_000)
            activity.startActivity(Intent(activity.intent)
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.waitUntil(10000) { !activity.isInPictureInPictureMode }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(expired, controller().isLocked)
            assertEquals("relay gate must follow PIN state on PiP expansion", expired, suppressed())
        }
        if (expired) {
            compose.onNodeWithText(context.getString(R.string.lock_message_locked)).assertIsDisplayed()
            "1234".forEach { compose.onNodeWithText(it.toString(), useUnmergedTree = true).performClick() }
            // On a short window the keypad scrolls; the action can start below the visible area.
            compose.onNodeWithText(context.getString(R.string.lock_action_unlock)).performScrollTo().performClick()
            // The relay input gate follows the unlock through recomposition, not synchronously.
            var state = ""
            try {
                compose.waitUntil(5_000) {
                    var settled = false
                    compose.runOnUiThread {
                        state = "locked=${controller().isLocked} suppressed=${suppressed()}"
                        settled = !controller().isLocked && !suppressed()
                    }
                    settled
                }
            } catch (failure: Throwable) { throw AssertionError("PIN unlock must resume relay input: $state", failure) }
        }
    }

    @Test fun homeAutomaticallyEntersPipInProductionActivity() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(60)
            assertTrue(controller().unlockWithPin("1234"))
            settings().saveLastBackgroundAt(0)
            host().configure(true, true, 640, 480)
        }
        val beforeHome = System.currentTimeMillis()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
        ).use { it.readBytes() }
        awaitPip()
        instrumentation.runOnMainSync {
            assertFalse(controller().isLocked)
            assertTrue(settings().getLastBackgroundAt() >= beforeHome)
            assertTrue(suppressed())
            activity.startActivity(Intent(activity.intent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.waitUntil(10000) { !activity.isInPictureInPictureMode }
        compose.runOnUiThread { assertFalse(controller().isLocked); assertFalse(suppressed()) }
    }

    @Test fun screenOffOnCannotResumeExpiredPipWithoutPin() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(60)
            assertTrue(controller().unlockWithPin("1234"))
            host().configure(true, false, 640, 480)
            assertTrue(host().enter())
        }
        awaitPip()
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            shell("input keyevent 223") // SLEEP: affects only the dedicated test Android device.
            SystemClock.sleep(1000)
            instrumentation.runOnMainSync { settings().saveLastBackgroundAt(System.currentTimeMillis() - 61_000) }
            shell("input keyevent 224") // WAKEUP
            shell("wm dismiss-keyguard")
            compose.waitUntil(20_000) { controller().isLocked }
            instrumentation.runOnMainSync {
                assertTrue(suppressed())
                val client = ConnectionWorkspace.get(activity.application).client
                client.onAppForeground()
                client.setRemotePaused(false)
                assertTrue("Normal foreground/navigation must not override the process lock", controller().isLocked)
                assertFalse(client.remoteInputSink.sendRemoteKey(65, true))
                activity.startActivity(Intent(activity.intent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            compose.waitUntil(20_000) { !activity.isInPictureInPictureMode }
            compose.onNodeWithText(context.getString(R.string.lock_message_locked)).assertIsDisplayed()
        } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
    }

    @Test fun timeoutExpiresWhileStillInPip() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(2)
            assertTrue(controller().unlockWithPin("1234"))
            settings().saveLastBackgroundAt(0)
            host().configure(true, false, 640, 480)
            assertTrue(host().enter())
        }
        awaitPip()
        compose.waitUntil(10000) { controller().isLocked }
        assertTrue("Timer must lock without expanding PiP", activity.isInPictureInPictureMode)
        compose.mainClock.advanceTimeBy(1000)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        assertLockedPipPixels()
        instrumentation.runOnMainSync {
            assertTrue(suppressed())
            activity.startActivity(Intent(activity.intent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        compose.waitUntil(10000) { !activity.isInPictureInPictureMode }
        compose.onNodeWithText(context.getString(R.string.lock_message_locked)).assertIsDisplayed()
    }

    @Test fun shareActivityCannotReplaceExpiredPipLockState() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(60)
            assertTrue(controller().unlockWithPin("1234"))
            host().configure(true, false, 640, 480)
            assertTrue(host().enter())
        }
        awaitPip()
        val first = activity
        val firstLock = controller()
        instrumentation.runOnMainSync {
            settings().saveLastBackgroundAt(System.currentTimeMillis() - 61_000)
            activity.startActivity(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Synthetic shared text")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            })
        }
        compose.waitUntil(10000) { activity !== first && !activity.isInPictureInPictureMode }
        val second = activity
        try {
            instrumentation.runOnMainSync {
                assertTrue("Expired PiP must lock the first Activity when the share task opens", firstLock.isLocked)
                assertTrue(controller().isLocked)
                assertTrue(controller().unlockWithPin("1234"))
                assertFalse("Unlock in the share Activity must unlock the first instance too", firstLock.isLocked)
                controller().lockNow()
                assertTrue(firstLock.isLocked)
            }
        } finally {
            instrumentation.runOnMainSync {
                second.finish()
                first.startActivity(Intent(first.intent).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        compose.waitUntil(10000) { activity !== second && !activity.isInPictureInPictureMode }
        instrumentation.runOnMainSync { assertTrue(firstLock.isLocked); assertTrue(suppressed()) }
    }

    @Test fun closingPipAfterStopDoesNotReRegisterTheStoppedWindow() {
        val lock = compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(1)
            AppLockController(settings(), SecureVault(context)).also { controller ->
                assertTrue(controller.unlockWithPin("1234"))
                val window = Any()
                controller.onWindowStarted(window, inPip = true)
                // The PiP close button stops the Activity before it leaves picture-in-picture mode.
                controller.onWindowStopped(window)
                controller.onPipChanged(window, inPip = false)
                assertFalse(controller.hasVisibleWindow)
            }
        }
        SystemClock.sleep(1500)
        compose.runOnUiThread {
            lock.onAppForegrounded()
            assertTrue("Reopening after the timeout must require the PIN", lock.isLocked)
        }
    }

    @Test fun visiblePausedWindowDoesNotStartLockCountdown() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(1)
            assertTrue(controller().unlockWithPin("1234"))
            settings().saveLastBackgroundAt(0)
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        SystemClock.sleep(1500)
        instrumentation.runOnMainSync {
            assertEquals("Visible paused split-screen is not background", 0L, settings().getLastBackgroundAt())
            assertFalse(controller().isLocked)
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.runOnUiThread { assertFalse(controller().isLocked) }
    }

    private fun awaitPip() {
        // The platform flag flips before callbacks/recreation finish. Do not start the next
        // security step against the old Activity while Android is still entering PiP.
        compose.waitUntil(10000) { activity.isInPictureInPictureMode && host().active && settings().getLastBackgroundAt() > 0 }
        SystemClock.sleep(700)
        instrumentation.waitForIdleSync()
        assertTrue(activity.isInPictureInPictureMode)
    }

    private fun assertLockedPipPixels() {
        val location = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            decor.getLocationOnScreen(location)
            width = decor.width; height = decor.height
            val client = ConnectionWorkspace.get(activity.application).client
            val relay = client.javaClass.getDeclaredField("relay").apply { isAccessible = true }.get(client)
            assertTrue(relay.javaClass.getDeclaredField("appLocked").apply { isAccessible = true }.getBoolean(relay))
            assertNull(client.desktopState.value.framebuffer)
        }
        val screen = instrumentation.uiAutomation.takeScreenshot()
        var black = 0
        var white = 0
        var total = 0
        for (y in (location[1] + 8) until (location[1] + height - 8) step 3)
            for (x in (location[0] + 8) until (location[0] + width - 8) step 3) {
                if (x !in 0 until screen.width || y !in 0 until screen.height) continue
                val pixel = screen.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                if (maxOf(red, green, blue) < 20) black++
                if (minOf(red, green, blue) > 180) white++
                total++
            }
        screen.recycle()
        assertTrue("PiP must draw the black locked placeholder, not the desktop: $black/$total", total > 100 && black > total * 0.90)
        assertTrue("Locked placeholder must include its visible text", white > 4)
    }

    @Test fun ordinaryRecreationKeepsUnlockedStateButExpiredStopLocks() {
        compose.runOnUiThread {
            SecureVault(context).savePin("1234")
            settings().updateLockTimeoutSeconds(60)
            assertTrue(controller().unlockWithPin("1234"))
        }
        val original = controller()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnUiThread { assertSame(original, controller()); assertFalse(controller().isLocked) }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        SettingsRepository(context).saveLastBackgroundAt(System.currentTimeMillis() - 61_000)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnUiThread { assertTrue(controller().isLocked); assertTrue("PiP gate: platform=${activity.isInPictureInPictureMode} host=${host().active} locked=${controller().isLocked}", suppressed()) }
    }
}
