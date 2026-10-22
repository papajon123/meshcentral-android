package ru.papajon.meshcentralmobile.transfer

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.mesh.session.RelayConnection
import ru.papajon.meshcentralmobile.mesh.session.RelayFrame
import ru.papajon.meshcentralmobile.mesh.session.RelayPhase
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.RelayStatus

/** Queue semantics with a fake uploader; FilesController protocol paths live in the other test. */
class TransferQueueTest {
    private lateinit var scope: CoroutineScope
    private lateinit var controllerScope: CoroutineScope
    private lateinit var controller: FilesController
    private val queues = mutableListOf<TransferQueue>()

    @After fun cleanup() {
        queues.forEach { it.close() }
        controller.close()
        scope.cancel()
        controllerScope.cancel()
    }

    @Test fun enqueueIsBoundedToTwentyAndRejectsPathMismatch() = runBlocking {
        val uploader = FakeUploader()
        val (queue, target) = queue(uploader)
        val items = (1L..21L).map { TransferItem(it, contentUri(it), "file-$it.bin", 1) }

        assertFalse(queue.enqueueConfirmed(target, items.take(21)))
        assertTrue(queue.state.value.isEmpty())

        assertTrue(queue.enqueueConfirmed(target, items.take(20)))
        assertEquals(20, queue.state.value.size)
        waitFor { queue.state.value.first().status == TransferStatus.Active }
        assertTrue(queue.active)
        assertTrue(queue.hasPending)
        assertTrue(queue.state.value.active)
        assertTrue(queue.state.value.hasPending)
        assertNotNull(withTimeout(3_000) { uploader.calls.receive() })

        val wrongDirectory = TransferTarget(
            profileId = target.profileId,
            profileName = target.profileName,
            nodeId = target.nodeId,
            nodeName = target.nodeName,
            directory = "/other",
            controller = controller,
        )
        assertFalse(queue.enqueueConfirmed(wrongDirectory, listOf(TransferItem(99, contentUri(99), "x.bin"))))
        assertEquals(20, queue.state.value.size)
    }

    @Test fun queueCannotMixControllersNodesOrDirectories() = runBlocking {
        val uploader = FakeUploader()
        val (queue, target) = queue(uploader)
        assertTrue(queue.enqueueConfirmed(target, listOf(TransferItem(1, contentUri(1), "one.bin"))))
        assertNotNull(withTimeout(3_000) { uploader.calls.receive() })

        val otherNode = TransferTarget(target.profileId, target.profileName, "other-node", target.nodeName, "", controller)
        assertFalse(queue.enqueueConfirmed(otherNode, listOf(TransferItem(2, contentUri(2), "two.bin"))))
        assertEquals(1, queue.state.value.size)
    }

    @Test fun cancelledQueuedItemIsNeverSentAndOthersStillRun() = runBlocking {
        val uploader = FakeUploader()
        val (queue, target) = queue(uploader)
        assertTrue(queue.enqueueConfirmed(target, listOf(
            TransferItem(1, contentUri(1), "one.bin"),
            TransferItem(2, contentUri(2), "two.bin"),
        )))
        val first = withTimeout(3_000) { uploader.calls.receive() }
        assertEquals(1L, first.item.id)

        assertTrue(queue.cancel(2L))
        assertEquals(TransferStatus.Cancelled, queue.state.value.single { it.id == 2L }.status)
        first.result.complete(TransferOutcome.Success(10))
        waitFor { queue.state.value.single { it.id == 1L }.status == TransferStatus.Succeeded }
        assertNull(withTimeoutOrNull(300) { uploader.calls.receive() })
        assertFalse(queue.hasPending)
    }

    @Test fun retryOnlyAcceptsFailedOrCancelledAndRestartsFromZero() = runBlocking {
        val uploader = FakeUploader()
        val (queue, target) = queue(uploader)
        assertTrue(queue.enqueueConfirmed(target, listOf(TransferItem(1, contentUri(1), "one.bin"))))
        val first = withTimeout(3_000) { uploader.calls.receive() }
        first.progress(4)
        waitFor { queue.state.value.single().bytes == 4L }
        first.result.complete(TransferOutcome.Failed(UiText.resource(R.string.transfer_upload_failed)))
        waitFor { queue.state.value.single().status == TransferStatus.Failed }
        assertTrue(queue.retry(1L))
        assertEquals(0L, queue.state.value.single().bytes)
        val second = withTimeout(3_000) { uploader.calls.receive() }
        assertFalse(queue.retry(1L))
        second.result.complete(TransferOutcome.Success(4))
        waitFor { queue.state.value.single().status == TransferStatus.Succeeded }
        assertFalse(queue.retry(1L))
        assertEquals(1, queue.clearFinished())
        assertTrue(queue.state.value.isEmpty())
    }

    @Test fun closeCancelsTheChildScopeAndLeavesNoPendingRow() = runBlocking {
        val uploader = FakeUploader()
        val (queue, target) = queue(uploader)
        assertTrue(queue.enqueueConfirmed(target, listOf(
            TransferItem(1, contentUri(1), "one.bin"),
            TransferItem(2, contentUri(2), "two.bin"),
        )))
        assertNotNull(withTimeout(3_000) { uploader.calls.receive() })
        queue.close()
        waitFor { queue.state.value.none { it.status == TransferStatus.Queued || it.status == TransferStatus.Active } }
        assertFalse(queue.hasPending)
        assertNull(withTimeoutOrNull(300) { uploader.calls.receive() })
    }

    private suspend fun queue(uploader: FakeUploader): Pair<TransferQueue, TransferTarget> {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val relay = NoopRelay()
        controller = FilesController(relay, controllerScope, InstrumentationRegistry.getInstrumentation().targetContext)
        val queue = TransferQueue(scope, uploader)
        queues += queue
        return queue to TransferTarget("profile", "Profile", "node", "Node", "", controller)
    }

    private fun contentUri(id: Long): Uri = Uri.parse("content://queue.test/$id")

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(3_000) { while (!predicate()) delay(10) }
    }

    private class FakeUploader : QueuedUploader {
        class Call(
            val target: TransferTarget,
            val item: TransferItem,
            val progress: (Long) -> Unit,
            val result: CompletableDeferred<TransferOutcome> = CompletableDeferred(),
        )

        val calls = Channel<Call>(Channel.UNLIMITED)

        override suspend fun upload(
            target: TransferTarget,
            item: TransferItem,
            onProgress: (Long) -> Unit,
        ): TransferOutcome {
            val call = Call(target, item, onProgress)
            calls.trySend(call)
            return call.result.await()
        }
    }

    private class NoopRelay : RelayConnection {
        override val protocol = RelayProtocol.AgentFiles
        override val state = MutableStateFlow(RelayStatus())
        override val incoming = Channel<RelayFrame>(1).receiveAsFlow()
        override fun connect() = Unit
        override fun sendText(value: String) = false
        override fun sendBinary(value: ByteArray) = false
        override fun close() = Unit
    }
}
