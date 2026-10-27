package ru.papajon.meshcentralmobile.mesh.control

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** At most one read is active and one trailing read is queued, even during a long event burst. */
internal class CoalescingRefresh(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 200,
    private val refresh: suspend () -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private var dirty = false
    private val count = AtomicInteger()
    val executions: Int get() = count.get()
    val idle: Boolean get() = synchronized(lock) { job == null }

    fun request() {
        val launch = synchronized(lock) {
            dirty = true
            if (job != null) return
            scope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    do {
                        delay(debounceMillis)
                        val owned = synchronized(lock) { if (job !== owner) false else { dirty = false; true } }
                        if (!owned) break
                        currentCoroutineContext().ensureActive()
                        count.incrementAndGet()
                        try { refresh() }
                        catch (_: TimeoutCancellationException) { /* A later event may retry. */ }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* An event arriving during failure still earns one follow-up. */ }
                        val again = synchronized(lock) {
                            if (job !== owner) false else if (dirty) true else { job = null; false }
                        }
                    } while (again)
                } finally { synchronized(lock) { if (job === owner) job = null } }
            }.also { job = it }
        }
        launch.start()
    }

    fun cancel() {
        val previous = synchronized(lock) { job.also { job = null; dirty = false } }
        previous?.cancel()
    }
}
