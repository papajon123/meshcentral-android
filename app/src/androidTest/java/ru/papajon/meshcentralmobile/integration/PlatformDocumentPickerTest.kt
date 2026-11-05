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
 *
 * Foreground proof comes from Window Manager focus records read through an independent
 * shell oracle; the accessibility root is not used for cancellation decisions.
 */
class PlatformDocumentPickerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val oracle by lazy { SystemFocusOracle(automation) }

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
        var failure: Throwable? = null
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
            awaitDocumentsUiForeground(scenario)
            // CreateDocument may focus its filename editor and show the IME. A Back press
            // then dismisses the keyboard first; keep pressing only while DocumentsUI owns
            // the focused window and stop as soon as its callback arrives.
            cancelPickerWhileForeground(scenario, received)
            assertEquals("No cancellation result within 10 s", 0L, received.count)
            assertNull("Cancelling the picker must return a null Uri", result.get())
            awaitResumed(scenario)
        } catch (throwable: Throwable) {
            failure = throwable
        } finally {
            val cleanupFailure = cleanup(scenario, launcherRef)
            val primary = failure
            if (primary != null) {
                if (cleanupFailure != null) primary.addSuppressed(cleanupFailure)
                throw primary
            }
            if (cleanupFailure != null) throw cleanupFailure
        }
    }

    private fun awaitDocumentsUiForeground(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last: FocusComponent? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = oracle.readSnapshot().preferredComponent
            if (last?.isDocumentsUi == true && scenario.state != Lifecycle.State.RESUMED) return
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "DocumentsUI did not become the foreground window " +
                "(last=${describe(last)}, activity=${scenario.state})",
        )
    }

    private fun cancelPickerWhileForeground(
        scenario: ActivityScenario<MainActivity>,
        received: CountDownLatch,
    ) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var backPresses = 0
        var last: FocusComponent? = null
        while (received.count != 0L && SystemClock.elapsedRealtime() < deadline) {
            val snapshot = oracle.readSnapshot()
            last = snapshot.preferredComponent
            val ownedPickerForeground = last?.isDocumentsUi == true &&
                scenario.state != Lifecycle.State.RESUMED &&
                received.count != 0L
            if (ownedPickerForeground) {
                if (backPresses >= MAX_BACK_PRESSES) {
                    throw AssertionError(
                        "DocumentsUI stayed foreground after $MAX_BACK_PRESSES Back presses " +
                            "(last=${describe(last)})",
                    )
                }
                assertTrue(
                    "Back was not delivered to DocumentsUI",
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
                )
                backPresses++
                awaitCallbackOrFocusChange(received, snapshot, deadline)
            } else {
                received.await(POLL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Waits for the result callback, for DocumentsUI to stop owning the focused window, or
     * for the focus record itself to change. The last case is IME dismissal: the focused-app
     * record keeps naming DocumentsUI while the component-less input window is replaced by
     * the picker's own activity component. The next iteration then sends the picker's Back.
     */
    private fun awaitCallbackOrFocusChange(
        received: CountDownLatch,
        before: WindowFocusSnapshot,
        overallDeadline: Long,
    ) {
        val sliceDeadline = minOf(overallDeadline, SystemClock.elapsedRealtime() + BACK_SETTLE_MS)
        val beforeSignature = focusSignature(before)
        while (received.count != 0L && SystemClock.elapsedRealtime() < sliceDeadline) {
            val after = oracle.readSnapshot()
            if (!after.documentsUiForeground) return
            if (focusSignature(after) != beforeSignature) return
            received.await(POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun focusSignature(snapshot: WindowFocusSnapshot): String =
        "${componentSignature(snapshot.currentFocus)}|${componentSignature(snapshot.focusedApp)}"

    private fun componentSignature(component: FocusComponent?): String =
        component?.let { "${it.packageName}/${it.activityName}" } ?: "none"

    private fun awaitResumed(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.state == Lifecycle.State.RESUMED) return
            SystemClock.sleep(POLL_MS)
        }
        assertEquals("MainActivity did not return to RESUMED", Lifecycle.State.RESUMED, scenario.state)
    }

    private fun <I> cleanup(
        scenario: ActivityScenario<MainActivity>,
        launcherRef: AtomicReference<ActivityResultLauncher<I>?>,
    ): Throwable? {
        val failures = mutableListOf<Throwable>()
        // Dismiss the owned picker first: unregistering removes the result callback that a
        // later Back press would deliver to. Only an observed DocumentsUI window is pressed.
        collectFailure(failures) { dismissOwnedPicker() }
        collectFailure(failures) {
            instrumentation.runOnMainSync { launcherRef.get()?.unregister() }
        }
        collectFailure(failures) { scenario.close() }
        if (failures.isEmpty()) return null
        val primary = failures.first()
        failures.drop(1).forEach { primary.addSuppressed(it) }
        return primary
    }

    private fun collectFailure(failures: MutableList<Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            failures += throwable
        }
    }

    private fun dismissOwnedPicker() {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var backPresses = 0
        var last: FocusComponent? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = oracle.readSnapshot().preferredComponent
            if (last?.isDocumentsUi != true) return
            if (backPresses >= MAX_CLEANUP_BACK_PRESSES) {
                throw AssertionError(
                    "DocumentsUI picker was still foreground after $backPresses cleanup Back presses " +
                        "(last=${describe(last)})",
                )
            }
            assertTrue(
                "Cleanup Back was not delivered while ${describe(last)} was foreground",
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
            )
            backPresses++
            awaitPickerDeparture(deadline)
        }
        throw AssertionError(
            "DocumentsUI picker was still foreground at the cleanup deadline (last=${describe(last)})",
        )
    }

    private fun awaitPickerDeparture(overallDeadline: Long) {
        val sliceDeadline = minOf(overallDeadline, SystemClock.elapsedRealtime() + BACK_SETTLE_MS)
        while (SystemClock.elapsedRealtime() < sliceDeadline) {
            if (oracle.readSnapshot().preferredComponent?.isDocumentsUi != true) return
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun describe(component: FocusComponent?): String =
        component?.toString() ?: "no focused activity component"

    private companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val POLL_MS = 100L
        private const val BACK_SETTLE_MS = 750L
        private const val MAX_BACK_PRESSES = 3
        private const val MAX_CLEANUP_BACK_PRESSES = 5
    }
}
