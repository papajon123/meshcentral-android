package ru.papajon.meshcentralmobile.mesh.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesReviewLogicTest {
    @Test fun deleteGroupsKeepRecursiveFilesAndFoldersSeparate() {
        val folder = RemoteFile("logs", 2)
        val file = RemoteFile("app.log", 3, 4)
        val groups = DeletionPlan.groups(listOf(folder, file))
        assertEquals(2, groups.size)
        assertTrue(groups[0].single().directory)
        assertFalse(groups[1].single().directory)
    }

    @Test fun deleteVerificationIgnoresSizeButRejectsTypeSwap() {
        val growingLog = RemoteFile("app.log", 3, 4)
        assertTrue(DeletionPlan.unchanged(growingLog, growingLog.copy(size = 99)))
        assertTrue(DeletionPlan.unchanged(growingLog, growingLog.copy(size = 0, sizeKnown = true)))
        assertFalse(DeletionPlan.unchanged(growingLog, growingLog.copy(type = 2)))
        assertFalse(DeletionPlan.unchanged(growingLog, null))
    }

    @Test fun zeroLengthEntriesAreCreatedWithoutRemoteOpen() {
        assertTrue(QueueSpecialFiles.createEmptyLocally(RemoteFile("fifo", 3, 0)))
        assertFalse(QueueSpecialFiles.createEmptyLocally(RemoteFile("data", 3, 1)))
        assertFalse(QueueSpecialFiles.createEmptyLocally(RemoteFile("unknown", 3, 0, sizeKnown = false)))
        assertFalse(QueueSpecialFiles.createEmptyLocally(RemoteFile("folder", 2)))
    }

    @Test fun unreadableDirectoryReplyIsRejectedForBothProtocols() {
        val nullDirectory = JSONObject("""{"path":"/fixture/closed","dir":null}""")
        assertTrue(runCatching { AgentFilesProtocol.entries(nullDirectory) }.isFailure)
        assertTrue(runCatching { SftpFilesAdapter.entries(nullDirectory) }.isFailure)
        val tooMany = JSONObject().put("path", "/fixture/big").put("dir", org.json.JSONArray().also { array ->
            repeat(AgentFilesProtocol.MAX_LIST_ENTRIES + 1) { array.put(JSONObject().put("n", "f$it").put("t", 3).put("s", 1)) }
        })
        assertTrue(runCatching { AgentFilesProtocol.entries(tooMany) }.isFailure)
    }

    @Test fun archiveIdleTimeoutWaitsForActivityAndIsNotAnAbsoluteDeadline() {
        runBlocking {
            val done = CompletableDeferred<Unit>()
            val activity = Channel<Unit>(Channel.CONFLATED)
            val feed = launch {
                repeat(6) { delay(35); activity.trySend(Unit) }
            }
            val finish = launch { delay(250); done.complete(Unit) }
            // 70 ms is shorter than the whole archive, but every zippingFile resets the deadline.
            assertTrue(awaitArchiveCompletion(done, activity, 70))
            feed.cancel(); finish.cancel(); activity.close()
        }
    }

    @Test fun archiveSilenceAfterZippingReportsIdleInsteadOfWaitingTenMinutes() {
        runBlocking {
            val done = CompletableDeferred<Unit>()
            val activity = Channel<Unit>(Channel.CONFLATED)
            activity.trySend(Unit) // `zipping` acknowledged, then the agent goes silent.
            val started = System.nanoTime()
            assertFalse(awaitArchiveCompletion(done, activity, 60))
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("idle timeout must fire promptly, took $elapsedMillis ms", elapsedMillis in 40..5_000)
            activity.close()
        }
    }
}
