package ru.papajon.meshcentralmobile.integration

import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity

/**
 * Exercises the real [MainActivity] ActivityResultRegistry: the launch goes through
 * FragmentActivity.startActivityForResult and the system DocumentsUI, not a fake registry.
 * A request-code regression is captured in the launch block and reported as a JUnit failure,
 * so the instrumentation process survives to report the red test.
 */
class PlatformDocumentPickerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun openDocumentLaunchesRealPickerAndBackReturnsNull() {
        probe(ActivityResultContracts.OpenDocument(), arrayOf("*/*"))
    }

    @Test
    fun createDocumentLaunchesRealPickerAndBackReturnsNull() {
        probe(ActivityResultContracts.CreateDocument("text/plain"), "meshpilot-picker-probe.txt")
    }

    private fun <I> probe(contract: ActivityResultContract<I, Uri?>, input: I) {
        val result = AtomicReference<Uri?>()
        val received = CountDownLatch(1)
        val launchFailure = AtomicReference<Throwable?>()
        val launcherRef = AtomicReference<ActivityResultLauncher<I>?>()
        val key = "platform-document-picker-${UUID.randomUUID()}"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                // The overload without a LifecycleOwner is what FilesScreen effectively uses.
                val launcher = activity.activityResultRegistry.register(key, contract) { uri ->
                    result.set(uri)
                    received.countDown()
                }
                launcherRef.set(launcher)
                try {
                    launcher.launch(input)
                } catch (throwable: Throwable) {
                    launchFailure.set(throwable)
                }
            }
            assertNull("Real picker launch failed: ${launchFailure.get()}", launchFailure.get())
            assertTrue("DocumentsUI did not become the active window", awaitDocumentsUi())
            Thread.sleep(SETTLE_MS)
            // CreateDocument may focus its filename editor and show the IME. The first Back
            // then closes the keyboard, not the picker. Never send Back after its callback.
            val cancelDeadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            var backPresses = 0
            while (received.count != 0L && SystemClock.elapsedRealtime() < cancelDeadline) {
                if (documentsUiActive() && backPresses < 3) {
                    assertTrue("Back was not delivered to DocumentsUI",
                        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                    backPresses++
                    received.await(600, TimeUnit.MILLISECONDS)
                } else {
                    received.await(POLL_MS, TimeUnit.MILLISECONDS)
                }
            }
            assertEquals("No cancellation result within 10 s", 0L, received.count)
            assertNull("Cancelling the picker must return a null Uri", result.get())
            awaitResumed(scenario)
        } finally {
            runCatching {
                instrumentation.runOnMainSync {
                    launcherRef.get()?.unregister()
                }
            }
            // Leave no system picker behind if an assertion failed before the user Back.
            repeat(3) {
                if (documentsUiActive()) {
                    runCatching { automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
                    Thread.sleep(300)
                }
            }
            scenario.close()
        }
    }

    private fun awaitDocumentsUi(): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (documentsUiActive()) return true
            Thread.sleep(POLL_MS)
        }
        return documentsUiActive()
    }

    private fun documentsUiActive(): Boolean {
        val activePackage = automation.rootInActiveWindow?.packageName?.toString() ?: return false
        return activePackage in DOCUMENTS_UI_PACKAGES
    }

    private fun awaitResumed(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (scenario.state == Lifecycle.State.RESUMED) return
            Thread.sleep(POLL_MS)
        }
        assertEquals("MainActivity did not return to RESUMED", Lifecycle.State.RESUMED, scenario.state)
    }

    private companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val POLL_MS = 100L
        private const val SETTLE_MS = 300L
        private val DOCUMENTS_UI_PACKAGES = setOf(
            "com.android.documentsui",
            "com.google.android.documentsui",
        )
    }
}
