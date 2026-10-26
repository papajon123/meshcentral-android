package ru.papajon.meshcentralmobile.web

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

class SessionCleanupTest {
    @Test fun repeatedCookieAndCacheCleanupDoesNoMainThreadDiskWork() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val violations = CopyOnWriteArrayList<String>()
        repeat(10) {
            withContext(Dispatchers.IO) {
                val fixture = File(context.cacheDir, "cleanup-fixture").also { it.mkdirs() }
                repeat(400) { index -> File(fixture, "$index.txt").writeText("synthetic cache") }
                CookieManager.getInstance().setCookie("https://mesh.example/", "fixture=synthetic")
            }
            withContext(Dispatchers.Main) {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyListener({ it.run() }) { violation -> violations.add(violation.javaClass.simpleName) }.build())
                try { MeshWebViewController.clearWebViewSession(context) }
                finally { StrictMode.setThreadPolicy(previous) }
            }
            withContext(Dispatchers.IO) {
                assertFalse(File(context.cacheDir, "cleanup-fixture").exists())
                assertNull(CookieManager.getInstance().getCookie("https://mesh.example/"))
            }
        }
        assertTrue("Main-thread violations: $violations", violations.isEmpty())
    }

    @Test fun cleanupIsSingleFlightAndFinishesBeforeSignInIsEnabled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val workspace = ConnectionWorkspace.get(app)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        workspace.credentialVault.saveCredentials("fixture", "synthetic", "https://mesh.example/")
        try {
            onMain {
                workspace.clearSavedSession {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                workspace.clearSavedSession { calls.incrementAndGet() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // This must run while the IO cleanup is deliberately stalled, proving input can run.
            onMain { assertTrue(workspace.clearingSession) }
            assertEquals(1, calls.get())
            release.countDown()
            val deadline = System.currentTimeMillis() + 15000
            while (workspace.clearingSession && System.currentTimeMillis() < deadline) Thread.sleep(20)
            onMain { assertFalse(workspace.clearingSession) }
            assertNull(workspace.credentialVault.getCredentials("https://mesh.example/"))
            assertTrue(workspace.client.clientState.value.requiresSignIn)
        } finally { release.countDown() }
    }

    private fun onMain(action: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { action() } }
}
