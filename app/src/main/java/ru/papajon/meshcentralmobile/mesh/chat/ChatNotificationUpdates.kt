package ru.papajon.meshcentralmobile.mesh.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owned. First update is immediate; bursts retain the latest trailing update. */
internal class ChatNotificationUpdates(
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 1_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastPublished: Long? = null
    private var pending: (() -> Unit)? = null
    private var job: Job? = null

    fun submit(update: () -> Unit) {
        pending = update
        if (job?.isActive == true) return
        job = scope.launch {
            val wait = lastPublished?.let { (intervalMillis - (now() - it)).coerceAtLeast(0) } ?: 0
            delay(wait)
            val latest = pending
            pending = null
            lastPublished = now()
            latest?.invoke()
        }
    }

    fun cancel() { job?.cancel(); job = null; pending = null }
}
