package ru.papajon.meshcentralmobile.mesh.control

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoalescingRefreshTest {
    @Test fun hundredsOfEventsBecomeOneReadAndOneTrailingRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CoalescingRefresh(this, 10) { calls++; if (calls == 1) { started.complete(Unit); release.await() } }
        repeat(300) { refresh.request() }
        started.await()
        repeat(300) { refresh.request() }
        assertEquals(1, calls)
        release.complete(Unit)
        withTimeout(2000) { while (!refresh.idle) delay(5) }
        assertEquals(2, calls)
    }
    @Test fun cancellationDiscardsQueuedWorkAndCanRestart() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CoalescingRefresh(this, 10) { calls++; if (calls == 1) { started.complete(Unit); wait.await() } }
        refresh.request(); started.await(); refresh.request(); refresh.cancel()
        assertTrue(refresh.idle)
        refresh.request()
        withTimeout(2000) { while (!refresh.idle) delay(5) }
        assertEquals(2, calls)
    }
    @Test fun failedReadDoesNotLoseAnEventQueuedDuringIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = CoalescingRefresh(this, 10) { calls++; if (calls == 1) { started.complete(Unit); release.await(); error("Synthetic failure") } }
        refresh.request(); started.await(); refresh.request(); release.complete(Unit)
        withTimeout(2000) { while (!refresh.idle) delay(5) }
        assertEquals(2, calls)
    }
}
