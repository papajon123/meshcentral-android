package ru.papajon.meshcentralmobile.mesh.files

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FilesBatchModelTest {
    @Test fun selectionRejectsDrivesAndUnsafeNamesAndClearsOnNavigation() {
        val selection = FileSelection()
        val file = RemoteFile("one.txt", 3, 8)
        selection.toggle("/a", file)
        selection.toggle("/a", RemoteFile("two.txt", 3, 9))
        assertEquals(setOf("one.txt", "two.txt"), selection.names)
        selection.toggle("/a", file)
        assertEquals(setOf("two.txt"), selection.names)
        selection.toggle("/a", RemoteFile("C:", 1))
        selection.toggle("/a", RemoteFile("../bad", 3))
        assertEquals(setOf("two.txt"), selection.names)
        selection.reconcile("/b", listOf(file))
        assertTrue(selection.names.isEmpty())
    }

    @Test fun failureDoesNotAutomaticallyRetryOrCountAsSuccess() {
        val queue = DownloadQueueModel(listOf("a", "b"))
        assertEquals(0, queue.next())
        queue.fail(0)
        assertEquals(1, queue.next())
        queue.succeed(1, 12)
        assertNull(queue.next())
        assertEquals(1, queue.state.completed)
        assertTrue(queue.retry(0))
        assertFalse(queue.retry(1))
        assertEquals(0, queue.next())
        queue.succeed(0, 8)
        assertEquals(2, queue.state.completed)
        assertEquals(20L, queue.state.bytes)
    }

    @Test fun cancelStopsPendingItemsAndDoesNotClaimCompleted() {
        val queue = DownloadQueueModel(listOf("a", "b", "c"))
        queue.next()
        queue.cancel()
        assertNull(queue.next())
        assertEquals(0, queue.state.completed)
        assertTrue(queue.state.rows.all { it.status == DownloadStatus.Cancelled })
    }

    @Test fun progressIsCoalescedAndCompletionStillPublishesExactBytes() {
        var clock = 0L
        val queue = DownloadQueueModel((1..1000).map { "file-$it" }, now = { clock })
        assertEquals(0, queue.next())
        assertTrue(queue.progress(0, 16_384))
        val snapshot = queue.state
        repeat(1000) { assertFalse(queue.progress(0, (it + 2) * 16_384L)) }
        assertSame(snapshot, queue.state)
        clock += 100_000_000
        assertTrue(queue.progress(0, 99_000))
        queue.succeed(0, 100_001)
        assertEquals(100_001L, queue.state.rows[0].bytes)
    }

    @Test fun pauseAndRetryAllNeverRepeatSuccessfulItems() {
        val queue = DownloadQueueModel(listOf("a", "b", "c"))
        queue.next(); queue.succeed(0, 10)
        queue.next(); queue.fail(1); queue.pauseQueued()
        assertEquals(DownloadStatus.Paused, queue.state.rows[2].status)
        assertTrue(queue.retryAll())
        assertEquals(1, queue.next()); queue.succeed(1, 20)
        assertEquals(2, queue.next())
        assertEquals(DownloadStatus.Succeeded, queue.state.rows[0].status)
    }

    @Test fun windowsAliasesAreReadOnlyButPosixDotsRemainUsable() {
        val raw = """{"path":"C:/tmp","dir":[{"n":"...","t":2},{"n":"a.","t":3,"s":1},{"n":"normal","t":3,"s":1}]}"""
        val windows = AgentFilesProtocol.entries(JSONObject(raw)).associateBy { it.name }
        assertFalse(windows.getValue("...").actionable)
        assertFalse(windows.getValue("a.").actionable)
        assertTrue(windows.getValue("normal").actionable)
        val posix = AgentFilesProtocol.entries(JSONObject(raw).put("path", "/tmp"))
        assertTrue(posix.all { it.actionable })
    }

    @Test fun archiveRepliesRequireExplicitNullAndRecognizeErrors() {
        assertEquals(ArchiveReply.Started, AgentArchiveProtocol.reply(JSONObject("""{"action":"dialogmessage","msg":"zipping"}""")))
        assertEquals(ArchiveReply.Closed, AgentArchiveProtocol.reply(JSONObject("""{"action":"dialogmessage","msg":null}""")))
        assertEquals(ArchiveReply.Ignored, AgentArchiveProtocol.reply(JSONObject("""{"action":"dialogmessage"}""")))
        assertEquals(ArchiveReply.Failed, AgentArchiveProtocol.reply(JSONObject("""{"action":"dialogmessage","msg":"ziperror"}""")))
        assertEquals(ArchiveReply.Refresh, AgentArchiveProtocol.reply(JSONObject("""{"action":"refresh"}""")))
        val request = AgentArchiveProtocol.request("/fixture", "folder", "meshpilot-test.zip")
        assertEquals("folder", request.getJSONArray("files").getString(0))
        assertEquals("meshpilot-test.zip", request.getString("output"))
    }
}
