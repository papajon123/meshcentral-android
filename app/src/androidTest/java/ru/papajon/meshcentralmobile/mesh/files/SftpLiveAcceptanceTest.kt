package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.FilesScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in only. Real OpenSSH + MeshCentral 1.3.1; credentials are seeded into app-private cache via stdin. */
class SftpLiveAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val tree get() = DocumentsContract.buildTreeDocumentUri(context.packageName + FixtureDocumentsProvider.AUTHORITY_SUFFIX, FixtureDocumentsProvider.ROOT_ID)
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).followRedirects(false).build()
    private var stage = "setup"
    private lateinit var fixture: JSONObject
    private lateinit var auth: JSONObject
    private lateinit var manifests: JSONObject

    private fun request(operation: String? = null): JSONObject {
        val builder = Request.Builder().url(BASE + if (operation == null) "/fixture" else "/op/$operation")
        if (operation != null) builder.post(ByteArray(0).toRequestBody())
        return http.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Owned fixture endpoint failed" }
            JSONObject(requireNotNull(it.body).string())
        }
    }

    private suspend fun scenario(body: suspend (FilesController) -> Unit) {
        assumeTrue("Explicit isolated SFTP live fixture is required", BuildConfig.DEBUG && InstrumentationRegistry.getArguments().getString("sftpLive") == "true")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("sftp-live-$name", mode)
        }
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        var files: FilesController? = null
        try {
            fixture = withContext(Dispatchers.IO) { request() }
            check(fixture.getString("serverUrl") == SERVER && fixture.getString("directory") == "/fixture")
            auth = JSONObject(File(context.cacheDir, "sftp-live-auth.json").readText())
            manifests = withContext(Dispatchers.IO) { request("reset").getJSONObject("manifests") }
            FixtureDocumentsProvider.resetForTest(context)
            stage = "local MeshCentral login"
            withContext(Dispatchers.Main) {
                client.configureServer(SERVER)
                client.setPinnedServerKey(auth.getString("serverPin"))
                client.login(SERVER, auth.getString("serverUsername"), auth.getString("serverPassword"))
            }
            val snapshot = withTimeout(45_000) { client.clientState.first { it.socketConnected && it.devicesCurrent && !it.requiresSignIn && it.devices.any { d -> d.nodeId == fixture.getString("sshNodeId") } } }
            val device = snapshot.devices.single { it.nodeId == fixture.getString("sshNodeId") }
            val factory = { client.createRelayConnection(device, RelayProtocol.SshFiles) }
            val c = FilesController(factory(), scope, context, connectionFactory = factory)
            files = c
            stage = "real SFTP authentication"
            c.connect(); authenticate(c); ready(c)
            compose.setContent { MeshCentralMobileTheme { FilesScreen(c, {}, Modifier.safeDrawingPadding()) } }
            body(c)
        } catch (error: Throwable) {
            runCatching { capture("failure-screen") }
            runCatching { evidence("failure-semantics", JSONObject().put("tree", compose.onRoot(useUnmergedTree = true).printToString())) }
            evidence("failure-location", JSONObject().put("stage", stage).put("type", error.javaClass.simpleName)
                .put("frames", org.json.JSONArray(error.stackTrace.take(8).map { it.toString() }))
                .put("connected", files?.state?.value?.connected)
                .put("confirmationPresent", files?.state?.value?.confirmation != null)
                .put("message", files?.state?.value?.message?.asString(context))
                .put("rows", org.json.JSONArray(files?.batch?.state?.value?.rows?.map {
                    JSONObject().put("name", it.name).put("status", it.status.name).put("detail", it.detail?.asString(context))
                }.orEmpty())))
            // Never let credential-bearing requests, assertion arguments or server exceptions reach test logs.
            throw AssertionError("Live SFTP acceptance failed at $stage (${error.javaClass.simpleName})")
        } finally {
            withContext(NonCancellable) {
                files?.close()
                files?.let { withTimeoutOrNull(10_000) { it.state.first { s -> !s.connected && !s.batchBusy } } }
                client.close(); vault.clearCredentials(); scope.cancel()
                FixtureDocumentsProvider.resetForTest(context)
                http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun authenticate(c: FilesController) {
        withTimeout(30_000) { c.state.first { it.authRequired || it.connected } }
        if (c.state.value.authRequired) c.authenticatePassword(auth.getString("sshUsername"), auth.getString("sshPassword"))
    }
    private suspend fun ready(c: FilesController) { withTimeout(30_000) { c.state.first { it.connected && !it.busy } } }
    private suspend fun browse(c: FilesController, path: String) {
        c.browse(path); withTimeout(30_000) { c.state.first { it.path == path && it.connected && !it.busy } }
    }
    private suspend fun done(c: FilesController, timeout: Long = 360_000) {
        withTimeout(timeout) { c.batch.state.first { it.rows.isNotEmpty() && !it.pending } }
        withTimeout(10_000) { c.state.first { !it.batchBusy } }
    }
    private fun choose(names: List<String>) {
        names.forEachIndexed { index, name ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            if (index == 0) compose.onNodeWithText(name).performTouchInput { longClick() }
            else compose.onNodeWithText(name).performClick()
        }
    }
    private fun button(id: Int) {
        val label = context.getString(id)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }
    private suspend fun awaitConfirmationDialog() = withTimeout(10_000) {
        val matcher = hasText(context.getString(R.string.files_confirm))
        // The real relay publishes on IO; let runBlocking process pending state collectors
        // instead of blocking its event loop inside Compose's synchronous waitUntil.
        while (true) {
            delay(50)
            if (runCatching { compose.onNode(matcher).assertIsDisplayed() }.isSuccess) return@withTimeout
        }
    }
    private suspend fun confirmDialog() {
        awaitConfirmationDialog()
        compose.onNodeWithText(context.getString(R.string.files_confirm)).performClick()
    }
    private suspend fun downloadFromUi(c: FilesController, names: List<String>) {
        choose(names); button(R.string.tools_download_selected)
        tapSystem("Show roots", description = true)
        tapSystem("Synthetic fixtures")
        tapSystem("Use this folder")
        withTimeout(10_000) {
            while (c.batch.state.value.choosing) {
                val allow = findSystem(instrumentation.uiAutomation.rootInActiveWindow, "Allow", false)
                if (allow != null) clickSystem(allow)
                delay(100)
            }
        }
    }

    @Test fun wholeFolderLeavesPartialAndExplicitRetryVerifiesTwoHundredHashes(): Unit = runBlocking {
        scenario { c ->
            stage = "download whole hostile source folder"
            browse(c, "/fixture")
            downloadFromUi(c, listOf("source")); done(c)
            assertEquals(DownloadStatus.Failed, c.batch.state.value.rows.single().status)
            val partial = documents(FixtureDocumentsProvider.ROOT_ID).single { it.name == "source.partial" }
            val firstCount = verifyGoodFiles(partial.id)
            assertEquals(200, firstCount)
            val partialHashes = verifyTree(partial.id, manifests.getJSONObject("source"), false)
            capture("whole-folder-partial")
            val first = JSONObject().put("goodFilesHashVerified", firstCount).put("partialMarked", true)
                .put("relayConnectedAfterDeniedDirectory", c.state.value.connected)
                .put("failure", c.batch.state.value.rows.single().detail?.asString(context))
            stage = "repair owned hazards and explicitly retry"
            manifests = withContext(Dispatchers.IO) { request("repair").getJSONObject("manifests") }
            button(R.string.review_retry_all)
            authenticate(c); done(c)
            assertEquals(DownloadStatus.Succeeded, c.batch.state.value.rows.single().status)
            val complete = documents(FixtureDocumentsProvider.ROOT_ID).single { it.name == "source" }
            assertEquals(200, verifyGoodFiles(complete.id))
            val retryHashes = verifyTree(complete.id, manifests.getJSONObject("source"))
            assertTrue(documents(FixtureDocumentsProvider.ROOT_ID).any { it.name == "source.partial" })
            capture("whole-folder-retried")
            evidence("whole-folder", first.put("explicitRetrySucceeded", true).put("allPartialHashesVerified", partialHashes).put("allRetryHashesVerified", retryHashes))
        }
    }

    @Test fun linksDeniedFileAndFifoHaveHonestIndependentOutcomes(): Unit = runBlocking {
        scenario { c ->
            stage = "real symlinks followed by a normal file"
            browse(c, "/fixture/source/ab-links")
            val wire = c.state.value.entries.map { JSONObject().put("name", it.name).put("type", it.type).put("size", it.size) }
            downloadFromUi(c, listOf("broken", "good-dir", "good-file", "loop", "z-good.txt")); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(4, c.batch.state.value.rows.count { it.status == DownloadStatus.Failed })
            assertEquals(DownloadStatus.Succeeded, c.batch.state.value.rows.single { it.name == "z-good.txt" }.status)
            verifyTree(FixtureDocumentsProvider.ROOT_ID, JSONObject().put("z-good.txt", manifests.getJSONObject("source").getJSONObject("ab-links/z-good.txt")))
            assertTrue(c.batch.state.value.rows.filter { it.status == DownloadStatus.Failed }.all { it.detail?.asString(context)?.contains(it.name) == true })
            c.batch.state.value.rows.filter { it.status == DownloadStatus.Failed }.forEach { row ->
                val reason = if (row.name == "good-file") R.string.files_sftp_unverified else R.string.sftp_live_download_refused
                assertTrue(row.detail!!.asString(context).contains(context.getString(reason)))
            }
            val linkReasons = org.json.JSONArray(c.batch.state.value.rows.filter { it.status == DownloadStatus.Failed }.map {
                JSONObject().put("name", it.name).put("reason", it.detail?.asString(context))
            })
            capture("links-file-refusals")
            stage = "unreadable regular file followed by a normal file"
            browse(c, "/fixture/source")
            downloadFromUi(c, listOf("ac-denied-file.bin", "zzzz-after.txt")); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Succeeded), c.batch.state.value.rows.map { it.status })
            assertTrue(c.batch.state.value.rows.first().detail!!.asString(context).contains(context.getString(R.string.sftp_live_download_refused)))
            verifyTree(FixtureDocumentsProvider.ROOT_ID, JSONObject()
                .put("z-good.txt", manifests.getJSONObject("source").getJSONObject("ab-links/z-good.txt"))
                .put("zzzz-after.txt", manifests.getJSONObject("source").getJSONObject("zzzz-after.txt")))
            val keptDocument = documents(FixtureDocumentsProvider.ROOT_ID).single { it.name == "zzzz-after.txt" }.id
            stage = "retry only the failed row without repeating the successful file"
            withContext(Dispatchers.IO) { request("repair") }
            button(R.string.review_retry_all); authenticate(c); done(c)
            assertEquals(2, c.batch.state.value.completed)
            assertEquals(keptDocument, documents(FixtureDocumentsProvider.ROOT_ID).single { it.name == "zzzz-after.txt" }.id)
            verifyTree(FixtureDocumentsProvider.ROOT_ID, JSONObject()
                .put("z-good.txt", manifests.getJSONObject("source").getJSONObject("ab-links/z-good.txt"))
                .put("zzzz-after.txt", manifests.getJSONObject("source").getJSONObject("zzzz-after.txt"))
                .put("ac-denied-file.bin", manifests.getJSONObject("source").getJSONObject("ac-denied-file.bin")))
            withContext(Dispatchers.IO) { request("reset") }
            browse(c, "/fixture"); browse(c, "/fixture/source")
            stage = "unreadable directory closes the upstream relay and pauses the next file"
            downloadFromUi(c, listOf("ad-denied-dir", "zzzz-after.txt")); done(c, 60_000)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Paused), c.batch.state.value.rows.map { it.status })
            assertFalse(c.state.value.connected)
            val directoryReason = c.batch.state.value.rows.first().detail?.asString(context)
            assertTrue(directoryReason?.contains("ad-denied-dir") == true)
            capture("denied-directory-paused")
            withContext(Dispatchers.IO) { request("allow-directory") }
            button(R.string.review_retry_all); authenticate(c); done(c)
            assertEquals(2, c.batch.state.value.completed)
            browse(c, "/fixture"); browse(c, "/fixture/source")
            stage = "FIFO never opens: deadline pauses the remaining queue"
            downloadFromUi(c, listOf("zz-pipe", "zzzz-after.txt")); done(c, 60_000)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Paused), c.batch.state.value.rows.map { it.status })
            assertFalse(c.state.value.connected)
            val fifoReason = c.batch.state.value.rows.first().detail?.asString(context)
            assertTrue(fifoReason?.contains(context.getString(R.string.files_timeout)) == true)
            capture("fifo-paused")
            evidence("hazards", JSONObject().put("wireEntries", org.json.JSONArray(wire)).put("linkFailures", 4)
                .put("normalFileAfterLinksSucceeded", true).put("permissionFailureKeptRelay", true)
                .put("failedRowRetrySucceeded", true).put("successfulDocumentRetainedOnRetry", true)
                .put("linkReasons", linkReasons).put("deniedDirectoryReason", directoryReason)
                .put("deniedDirectoryPausedThenExplicitRetrySucceeded", true)
                .put("fifoReason", fifoReason).put("fifoTimedOutAndPausedQueue", true))
        }
    }

    @Test fun cancellationAndChangedTypeCannotDeleteUnconfirmedContents(): Unit = runBlocking {
        scenario { c ->
            stage = "cancel a real 24 MiB SFTP download"
            browse(c, "/fixture/cancel-test")
            downloadFromUi(c, listOf("a-big.bin", "b-later.txt"))
            withTimeout(20_000) { c.batch.state.first { it.rows.any { row -> row.status == DownloadStatus.Active && row.bytes > 0 } } }
            button(R.string.files_cancel_operation); done(c)
            assertTrue(c.batch.state.value.rows.all { it.status == DownloadStatus.Cancelled })
            assertTrue(documents(FixtureDocumentsProvider.ROOT_ID).isEmpty())
            capture("queue-cancelled")
            stage = "explicit retry reconnects after cancellation"
            button(R.string.review_retry_all); authenticate(c); done(c)
            assertEquals(2, c.batch.state.value.completed)
            assertEquals(2, verifyTree(FixtureDocumentsProvider.ROOT_ID, manifests.getJSONObject("cancel-test")))
            stage = "replace a confirmed file with a nonempty directory"
            browse(c, "/fixture/delete-test")
            choose(listOf("x.txt", "logs")); button(R.string.tools_delete_selected)
            withTimeout(5000) { c.state.first { it.confirmation != null } }
            assertEquals(2, c.state.value.confirmation!!.entries.size)
            awaitConfirmationDialog()
            capture("delete-confirmation-before-swap")
            withContext(Dispatchers.IO) { request("swap") }
            confirmDialog()
            withTimeout(30_000) { c.state.first { it.confirmation == null && !it.batchBusy } }
            val afterSwap = withContext(Dispatchers.IO) { request("inspect") }
            assertTrue(afterSwap.getBoolean("replacementIntact")); assertTrue(afterSwap.getBoolean("keepIntact"))
            assertEquals(context.getString(R.string.review_source_changed), c.state.value.message?.asString(context))
            capture("changed-type-rejected")
            stage = "confirmed regular file and empty directory deletion"
            choose(listOf("ordinary.txt", "empty-dir")); button(R.string.tools_delete_selected)
            withTimeout(5000) { c.state.first { it.confirmation != null } }
            confirmDialog()
            withTimeout(30_000) { c.state.first { it.confirmation == null && !it.batchBusy && !it.busy } }
            val afterDelete = withContext(Dispatchers.IO) { request("inspect") }
            assertFalse(afterDelete.getBoolean("ordinaryPresent")); assertFalse(afterDelete.getBoolean("emptyDirectoryPresent"))
            assertTrue(afterDelete.getBoolean("replacementIntact")); assertTrue(afterDelete.getBoolean("keepIntact"))
            capture("confirmed-group-deleted")
            evidence("cancel-and-delete", JSONObject().put("cancelRemovedOwnedDocuments", true).put("retryCompleted", 2)
                .put("afterTypeSwap", afterSwap).put("afterConfirmedDeletion", afterDelete))
        }
    }

    private data class Document(val id: String, val name: String, val directory: Boolean)
    private fun documents(parent: String): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Document(cursor.getString(0), cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)) }
        }
    }
    private fun verifyGoodFiles(sourceId: String): Int {
        val expected = fixture.getJSONObject("manifest")
        val good = documents(sourceId).single { it.name == "aa-good" && it.directory }
        return verifyTree(good.id, expected, prefix = "aa-good/")
    }
    private fun verifyTree(rootId: String, expected: JSONObject, requireAll: Boolean = true, prefix: String = ""): Int {
        val found = mutableSetOf<String>()
        fun visit(id: String, prefix: String) {
            for (doc in documents(id)) {
                val relative = prefix + doc.name
                if (doc.directory) visit(doc.id, "$relative/")
                else {
                    stage = "SHA-256 of $relative"
                    assertTrue("Unexpected ordinary fixture path", expected.has(relative)); assertTrue(found.add(relative))
                    val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))!!.use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); count += n }
                    }
                    val item = expected.getJSONObject(relative)
                    assertEquals(item.getLong("size"), count)
                    assertEquals(item.getString("sha256"), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                }
            }
        }
        visit(rootId, prefix)
        if (requireAll) assertEquals(expected.length(), found.size)
        return found.size
    }
    private fun evidence(name: String, value: JSONObject) {
        value.put("recordedAtMillis", System.currentTimeMillis())
        File(context.getExternalFilesDir(null), "sftp-live/$name.json").apply { parentFile!!.mkdirs(); writeText(value.toString(2)) }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        android.os.SystemClock.sleep(100) // Let the real SurfaceFlinger frame catch up with Compose's test clock.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "sftp-live/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    private fun findSystem(node: AccessibilityNodeInfo?, label: String, description: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEnabled &&
            (if (description) node.contentDescription else node.text)?.toString()?.equals(label, true) == true && clickableSystem(node) != null) return node
        repeat(node.childCount) { findSystem(node.getChild(it), label, description)?.let { found -> return found } }
        return null
    }
    private fun clickableSystem(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        while (!current.isClickable) current = current.parent ?: return null
        return current.takeIf { it.isEnabled && it.isVisibleToUser }
    }
    private fun clickSystem(node: AccessibilityNodeInfo): Boolean = clickableSystem(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    private suspend fun tapSystem(label: String, description: Boolean = false) = withTimeout(10_000) {
        val previousStage = stage
        stage = "$previousStage: system picker $label"
        while (true) {
            findSystem(instrumentation.uiAutomation.rootInActiveWindow, label, description)?.let {
                if (clickSystem(it)) { stage = previousStage; return@withTimeout }
            }
            delay(100)
        }
    }
    companion object {
        private const val BASE = "http://10.0.2.2:19867"
        private const val SERVER = "https://10.0.2.2:4467/"
    }
}
