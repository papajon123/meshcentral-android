package ru.papajon.meshcentralmobile.mesh.files

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.asString

class FilesReviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree get() = DocumentsContract.buildTreeDocumentUri(context.packageName + FixtureDocumentsProvider.AUTHORITY_SUFFIX, FixtureDocumentsProvider.ROOT_ID)
    private suspend fun ready(c: FilesController) { withTimeout(5000) { c.state.first { it.connected && !it.busy } } }
    private suspend fun done(c: FilesController, limit: Long = 10_000) {
        withTimeout(limit) { c.batch.state.first { it.rows.isNotEmpty() && !it.pending } }
        withTimeout(limit) { c.state.first { !it.batchBusy } }
    }
    private fun names(parent: String = FixtureDocumentsProvider.ROOT_ID): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(0)) }
        }
    }
    private fun bytes(id: String): ByteArray =
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))!!.use { it.readBytes() }
    private suspend fun fixture(relay: BatchRelay, block: suspend (FilesController) -> Unit) {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = FilesController(relay, scope, context, operationTimeoutMillis = 300,
            uploadCancelTimeoutMillis = 310, archiveStartTimeoutMillis = 100, archiveCancelTimeoutMillis = 500)
        try { controller.connect(); ready(controller); block(controller) }
        finally { controller.close(); withTimeout(3000) { controller.state.first { !it.connected } }; scope.cancel(); FixtureDocumentsProvider.resetForTest(context); ArchiveCleanupNotice.pending.value.toList().forEach(ArchiveCleanupNotice::dismiss) }
    }

    @Test fun fileRefusalDoesNotCloseRelayOrLoseLaterFiles() = runBlocking {
        val relay = BatchRelay().apply {
            files["/fixture/a-denied"] = byteArrayOf(1); files["/fixture/b-good"] = byteArrayOf(2)
            failPaths += "/fixture/a-denied"
        }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Succeeded), c.batch.state.value.rows.map { it.status })
            assertTrue(c.batch.state.value.rows[0].detail!!.asString(context).contains("/fixture/a-denied"))
            val refusal = c.batch.state.value.rows[0].detail!!.asString(context)
            assertTrue(refusal.contains(context.getString(R.string.sftp_live_download_refused)))
            assertFalse(refusal.contains(context.getString(R.string.files_operation_failed)))
            assertEquals(setOf("b-good"), names().keys)
        }
    }

    @Test fun singleDocumentIsRemovedWhenReconnectCannotStart() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/file"] = byteArrayOf(1) }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries, singleDocument = true))
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, FixtureDocumentsProvider.ROOT_ID)
            val doc = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "single")!!
            relay.close()
            withTimeout(5000) { c.state.first { !it.connected } }
            c.batch.chosen(doc, isDocument = true); done(c)
            assertEquals(DownloadStatus.Paused, c.batch.state.value.rows.single().status)
            assertTrue(names().isEmpty())
        }
    }

    @Test fun immediateCancellationReleasesReservationAndOwnedDocument() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/file"] = byteArrayOf(1); stallDownload = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries, singleDocument = true))
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, FixtureDocumentsProvider.ROOT_ID)
            val doc = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "single")!!
            c.batch.chosen(doc, isDocument = true)
            c.batch.cancel(); done(c)
            assertFalse(c.state.value.batchBusy)
            assertTrue(names().isEmpty())
        }
    }

    @Test fun cancelledPickerDiscardsItsLateSingleDocument() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/file"] = byteArrayOf(1) }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries, singleDocument = true))
            c.batch.cancel()
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, FixtureDocumentsProvider.ROOT_ID)
            val doc = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "late")!!
            c.batch.chosen(doc, isDocument = true)
            assertTrue(names().isEmpty())
        }
    }

    @Test fun disconnectPausesBeforeCreatingFurtherDestinations() = runBlocking {
        val relay = BatchRelay().apply {
            files["/fixture/a-lost"] = byteArrayOf(1); files["/fixture/b-later"] = byteArrayOf(2)
            disconnectPaths += "/fixture/a-lost"
        }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Paused), c.batch.state.value.rows.map { it.status })
            assertEquals(1, relay.downloads)
            assertTrue(names().isEmpty())
        }
    }

    @Test fun sftpSymlinkSizeMismatchAndPermissionDenialLeaveMarkedFolderAndContinue() = runBlocking {
        // apprelays 1.3.1 removes longname/attrs.mode: both a link and a regular file are t:3.
        val relay = BatchRelay(sftp = true).apply {
            files["/fixture/folder/a-link"] = ByteArray(100) { 3 }; sizes["/fixture/folder/a-link"] = 7
            files["/fixture/folder/b-denied"] = byteArrayOf(4); failPaths += "/fixture/folder/b-denied"
            files["/fixture/folder/c-good"] = byteArrayOf(5)
        }
        fixture(relay) { c ->
            c.browse("/fixture"); withTimeout(5000) { c.state.first { it.path == "/fixture" && !it.busy } }
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(DownloadStatus.Failed, c.batch.state.value.rows.single().status)
            val partial = names().getValue("folder.partial")
            assertEquals(setOf("c-good"), names(partial).keys)
            assertTrue(c.batch.state.value.rows.single().detail!!.asString(context).contains("a-link"))
            assertTrue(relay.commands.any { it.optString("action") == "download" && it.optString("sub") == "stop" })
        }
    }

    @Test fun fifoThatNeverCompletesOpenTimesOutAndPausesRemainingQueue() = runBlocking {
        val relay = BatchRelay(sftp = true).apply {
            files["/fixture/a-fifo"] = byteArrayOf(); files["/fixture/b-later"] = byteArrayOf(1)
            stallDownload = true
        }
        fixture(relay) { c ->
            c.browse("/fixture"); withTimeout(5000) { c.state.first { it.path == "/fixture" && !it.busy } }
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertFalse(c.state.value.connected)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Paused), c.batch.state.value.rows.map { it.status })
            assertTrue(names().isEmpty())
        }
    }

    @Test fun changedFileTypePreventsRecursiveDeletionOfReplacement() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/x"] = byteArrayOf(1); files["/fixture/logs/a"] = byteArrayOf(2) }
        fixture(relay) { c ->
            c.proposeDelete(c.state.value.entries)
            withTimeout(2000) { c.state.first { it.confirmation != null } }
            relay.files.remove("/fixture/x"); relay.files["/fixture/x/unconfirmed"] = byteArrayOf(9)
            c.confirm("")
            withTimeout(3000) { c.state.first { it.confirmation == null && !it.batchBusy } }
            assertTrue(relay.files.containsKey("/fixture/x/unconfirmed"))
            assertFalse(relay.commands.any { it.optString("action") == "rm" && it.getJSONArray("delfiles").toString().contains("\"x\"") })
        }
    }

    @Test fun changedFileSizeDoesNotBlockDeletion() = runBlocking {
        // A growing log keeps the same type. Size is not an identity check for `rec:false`.
        val relay = BatchRelay().apply { files["/fixture/x"] = byteArrayOf(1) }
        fixture(relay) { c ->
            c.proposeDelete(c.state.value.entries); withTimeout(2000) { c.state.first { it.confirmation != null } }
            relay.files["/fixture/x"] = byteArrayOf(1, 2)
            c.confirm(""); withTimeout(3000) { c.state.first { it.confirmation == null && !it.batchBusy } }
            assertEquals(1, relay.deletes)
            assertFalse(relay.files.containsKey("/fixture/x"))
            assertTrue(c.state.value.connected)
        }
    }

    @Test fun deleteSendsOneRequestPerRecursiveFlag() = runBlocking {
        val relay = BatchRelay().apply {
            files["/fixture/app.log"] = byteArrayOf(1)
            files["/fixture/logs/old"] = byteArrayOf(2)
        }
        fixture(relay) { c ->
            c.proposeDelete(c.state.value.entries); withTimeout(2000) { c.state.first { it.confirmation != null } }
            c.confirm("")
            withTimeout(5000) { c.state.first { it.confirmation == null && !it.batchBusy && relay.deletes >= 2 } }
            val rms = relay.commands.filter { it.optString("action") == "rm" }
            assertEquals(2, rms.size)
            assertEquals(setOf(false, true), rms.map { it.optBoolean("rec") }.toSet())
            assertTrue(rms.single { it.optBoolean("rec") }.getJSONArray("delfiles").toString().contains("logs"))
            assertTrue(rms.single { !it.optBoolean("rec") }.getJSONArray("delfiles").toString().contains("app.log"))
            assertTrue(relay.files.isEmpty())
            assertTrue(c.state.value.connected)
        }
    }

    @Test fun secondDeleteGroupReportsThatTheFirstWasAlreadyDeleted() = runBlocking {
        val relay = BatchRelay().apply {
            files["/fixture/app.log"] = byteArrayOf(1)
            files["/fixture/logs/old"] = byteArrayOf(2)
        }
        fixture(relay) { c ->
            relay.onDelete = { request ->
                if (request.optBoolean("rec")) {
                    // Swap the still-pending file for a directory before its own check.
                    relay.files.remove("/fixture/app.log")
                    relay.files["/fixture/app.log/inner"] = byteArrayOf(9)
                }
            }
            c.proposeDelete(c.state.value.entries); withTimeout(2000) { c.state.first { it.confirmation != null } }
            c.confirm("")
            withTimeout(5000) {
                c.state.first { it.confirmation == null && !it.batchBusy && it.message != null }
            }
            val message = c.state.value.message!!.asString(context)
            assertTrue(message.contains("Already deleted"))
            assertTrue(message.contains("logs"))
            assertTrue(relay.files.containsKey("/fixture/app.log/inner"))
            assertTrue(c.state.value.connected)
        }
    }

    @Test fun unreadableSubfolderFailsOnlyItsRowAndKeepsTheSession() = runBlocking {
        val relay = BatchRelay(sftp = true).apply {
            files["/fixture/folder/a"] = byteArrayOf(1)
            files["/fixture/good"] = byteArrayOf(2)
        }
        relay.nullListPaths += "/fixture/folder"
        fixture(relay) { c ->
            c.browse("/fixture"); withTimeout(5000) { c.state.first { it.path == "/fixture" && !it.busy } }
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(listOf(DownloadStatus.Failed, DownloadStatus.Succeeded), c.batch.state.value.rows.map { it.status })
            val detail = c.batch.state.value.rows[0].detail!!.asString(context)
            assertTrue(detail.contains("No access to folder"))
            assertTrue(detail.contains("/fixture/folder"))
            assertEquals(setOf("folder.partial", "good"), names().keys)
            assertArrayEquals(byteArrayOf(2), bytes(names().getValue("good")))
        }
    }

    @Test fun cancellingArchiveDownloadStopsItAndRemovesTheArchiveInTheSession() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(1); stallDownload = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree)
            withTimeout(5000) { relay.zipRequested.await() }
            withTimeout(5000) {
                while (relay.downloads == 0 || relay.files.keys.none { it.endsWith(".zip") }) delay(10)
            }
            c.batch.cancel(); done(c)
            assertTrue(c.state.value.connected)
            assertTrue(relay.downloadStops >= 1)
            assertTrue(relay.deletes >= 1)
            assertTrue(relay.files.keys.none { it.endsWith(".zip") })
            assertTrue(names().isEmpty())
        }
    }

    @Test fun zipSilentAfterZippingFallsBackToPerFileCopy() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(7); stallZip = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(1, c.batch.state.value.completed)
            assertTrue(relay.zipCancelled)
            assertTrue(relay.files.keys.none { it.endsWith(".zip") })
            val folder = names().getValue("folder")
            assertArrayEquals(byteArrayOf(7), bytes(names(folder).getValue("a")))
        }
    }

    @Test fun zeroLengthEntryIsWrittenWithoutRemoteOpen() = runBlocking {
        val relay = BatchRelay(sftp = true).apply { files["/fixture/folder/empty"] = byteArrayOf() }
        fixture(relay) { c ->
            c.browse("/fixture"); withTimeout(5000) { c.state.first { it.path == "/fixture" && !it.busy } }
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertEquals(1, c.batch.state.value.completed)
            assertEquals(0, relay.downloads)
            val folder = names().getValue("folder")
            assertArrayEquals(byteArrayOf(), bytes(names(folder).getValue("empty")))
        }
    }

    @Test fun closeDuringZipSendsCancelAndCleansBeforeDisconnect() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(1); stallZip = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree)
            withTimeout(2000) { relay.zipRequested.await() }
            c.close(); withTimeout(3000) { c.state.first { !it.connected } }
            assertTrue(relay.zipCancelled)
            assertFalse(relay.closeBeforeZipCancel)
            assertTrue(relay.files.keys.none { it.endsWith(".zip") })
        }
    }

    @Test fun unavailableZipFallsBackToFilesWithoutBreakingConnection() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(1); ignoreZip = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertTrue(c.state.value.connected)
            assertEquals(1, c.batch.state.value.completed)
            assertEquals(setOf("a"), names(names().getValue("folder")).keys)
        }
    }

    @Test fun corruptArchiveIsNotPublishedAsVerified() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(1); corruptZip = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertEquals(0, c.batch.state.value.completed)
            assertEquals(DownloadStatus.Failed, c.batch.state.value.rows.single().status)
            assertTrue(names().isEmpty())
            assertTrue(relay.files.keys.none { it.endsWith(".zip") })
        }
    }

    @Test fun archiveCleanupFailureKeepsVerifiedDownloadAndWarns() = runBlocking {
        val relay = BatchRelay().apply { files["/fixture/folder/a"] = byteArrayOf(1); stallDelete = true }
        fixture(relay) { c ->
            assertTrue(c.batch.prepare(c.state.value.entries)); c.batch.chosen(tree); done(c)
            assertEquals(1, c.batch.state.value.completed)
            assertTrue(names().containsKey("folder.zip"))
            val warning = c.batch.state.value.rows.single().detail!!.asString(context)
            val archive = relay.files.keys.single { it.endsWith(".zip") }
            assertTrue(warning.contains(archive))
            assertFalse(c.batch.retry(0))
            assertEquals(1, relay.downloads)
        }
    }

    @Test fun retryAtIdlePublicationWaitsForThePreviousJobToFinish() = runBlocking {
        for (single in listOf(false, true)) {
            FixtureDocumentsProvider.resetForTest(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val relay = BatchRelay().apply { files["/fixture/file"] = byteArrayOf(1, 2, 3); stallDownload = true }
            val c = FilesController(relay, scope, context, { BatchRelay(relay.files) })
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, FixtureDocumentsProvider.ROOT_ID)
            fun destination() = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "file")!!
            try {
                c.connect(); ready(c)
                assertTrue(c.batch.prepare(c.state.value.entries, singleDocument = single))
                c.batch.chosen(if (single) destination() else tree, isDocument = single)
                withTimeout(5000) { while (relay.downloads == 0) delay(10) }
                assertTrue(c.state.value.batchBusy)
                // Resume inline when idle is published, before the previous Job can complete.
                val retry = scope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    c.state.first { !it.batchBusy }
                    if (single) c.batch.prepareSingleRetry(0) == "file" else c.batch.retry()
                }
                c.batch.cancel()
                assertTrue("The first explicit retry must be accepted", withTimeout(5000) { retry.await() })
                if (single) c.batch.chosen(destination(), isDocument = true)
                done(c)
                assertEquals(1, c.batch.state.value.completed)
                val id = names().getValue("file")
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally {
                c.close(); withTimeout(5000) { c.state.first { !it.connected } }
                scope.cancel(); FixtureDocumentsProvider.resetForTest(context)
            }
        }
    }

    @Test fun retryAfterUploadCancelDeadlineUsesWorkingCollectors() = runBlocking {
        FixtureDocumentsProvider.resetForTest(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = BatchRelay().apply { stallUpload = true; ignoreUploadCancel = true }
        var fresh: BatchRelay? = null
        val c = FilesController(relay, scope, context, { BatchRelay(relay.files).also { fresh = it } },
            operationTimeoutMillis = 60_000)
        val source = FixtureDocumentsProvider.addForTest(context, "upload.bin", ByteArray(32_000) { 8 }, true, true)
        try {
            c.connect(); ready(c); c.proposeUpload(source); withTimeout(2000) { c.state.first { it.confirmation != null } }; c.confirm("upload.bin")
            withTimeout(2000) { relay.uploadRequested.await() }
            c.batch.cancel(); done(c, 40_000)
            assertFalse(c.state.value.connected)
            assertTrue(c.batch.retry()); done(c)
            assertEquals(1, c.batch.state.value.completed)
            assertEquals(1, fresh!!.uploads)
        } finally { c.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context); ArchiveCleanupNotice.pending.value.toList().forEach(ArchiveCleanupNotice::dismiss) }
    }
}
