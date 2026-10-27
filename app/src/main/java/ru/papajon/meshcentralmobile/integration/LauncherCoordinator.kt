package ru.papajon.meshcentralmobile.integration

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import ru.papajon.meshcentralmobile.mesh.MeshDevice

/**
 * One serialized IO queue for launcher JSON, ShortcutManager and widget work. Production code
 * never touches those stores on Main; tests can wait for quiescence with [awaitIdle].
 *
 * Every operation returns the enqueued job. Failures are swallowed by a silent exception handler:
 * raw exception text and device metadata must not reach the journal.
 */
object LauncherCoordinator {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queueScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            CoroutineExceptionHandler { _, _ -> /* Never log launcher metadata or raw errors. */ },
    )
    private val pending = MutableStateFlow(0)

    // Only touched by the single queue thread.
    private var lastWidgetSignature: String? = null
    private var publicationVersion = 0L
    data class RefreshTicket internal constructor(val snapshot: LauncherSnapshot, internal val version: Long)

    fun activate(context: Context, id: String): Job = enqueue {
        publicationVersion++
        LauncherStore(context).activate(id)
        renderNow(context)
    }

    fun clear(context: Context, id: String? = null): Job = enqueue {
        publicationVersion++
        DeviceShortcuts.clear(context, id)
        lastWidgetSignature = null
    }

    fun render(context: Context, force: Boolean = false): Job = enqueue {
        if (force) lastWidgetSignature = null
        renderNow(context)
    }

    fun publish(context: Context, profileId: String, scope: String, devices: List<MeshDevice>,
        favoriteIds: Set<String>, recentIds: List<String>, now: Long = System.currentTimeMillis(),
        canPublish: () -> Boolean = { true }): Job = enqueue {
        // The guard runs inside the queue, right before the write it protects.
        if (!canPublish()) return@enqueue
        LauncherStore(context).publish(profileId, scope, devices, favoriteIds, recentIds, now)
        publicationVersion++ // A fresh successful observation also supersedes failures when data is unchanged.
        writeWidgetStatus(context, false)
        renderNow(context)
    }

    suspend fun beginRefresh(context: Context): RefreshTicket? {
        val result = CompletableDeferred<RefreshTicket?>()
        enqueue {
            try { result.complete(LauncherStore(context).read()?.let { RefreshTicket(it, publicationVersion) }) }
            finally { if (!result.isCompleted) result.complete(null) }
        }
        return result.await()
    }

    private fun isCurrent(context: Context, ticket: RefreshTicket): Boolean =
        publicationVersion == ticket.version && LauncherStore(context).read() == ticket.snapshot

    /**
     * Runs the generation-guarded catalog refresh and the following render on the same serialized
     * queue. The render reads the snapshot after the guarded write, so a late response cannot
     * overwrite a logout/profile change that already happened on this queue.
     */
    suspend fun refresh(context: Context, ticket: RefreshTicket, devices: List<MeshDevice>): Boolean {
        val caller = currentCoroutineContext()
        val changed = CompletableDeferred<Boolean>()
        enqueue {
            try {
                val updated = caller.isActive && isCurrent(context, ticket) &&
                    LauncherStore(context).refresh(ticket.snapshot, devices)
                if (updated) {
                    publicationVersion++
                    writeWidgetStatus(context, false)
                    renderNow(context)
                }
                changed.complete(updated)
            } finally {
                if (!changed.isCompleted) changed.complete(false)
            }
        }
        return changed.await()
    }

    /** A late failure cannot replace another profile's status or a newer successful observation. */
    fun reportRefreshFailure(context: Context, ticket: RefreshTicket): Job = enqueue {
        if (isCurrent(context, ticket)) {
            writeWidgetStatus(context, true)
            renderNow(context)
        }
    }

    /** Transient feedback avoids leaving a frozen relative countdown in RemoteViews. */
    fun reportRefreshCooldown(context: Context, ticket: RefreshTicket, seconds: Long): Job = enqueue {
        if (seconds > 0 && isCurrent(context, ticket)) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context,
                    context.getString(ru.papajon.meshcentralmobile.R.string.integration_retry_seconds, seconds),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeWidgetStatus(context: Context, needsApp: Boolean) {
        val status = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE)
        if (!status.contains("needs_app") || status.getBoolean("needs_app", false) != needsApp) {
            status.edit().putBoolean("needs_app", needsApp).commit()
            lastWidgetSignature = null
        }
    }

    suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    private fun enqueue(block: suspend () -> Unit): Job {
        pending.update { it + 1 }
        return queueScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Deliberately silent; callers get a completed job and no secret/device details.
            } finally {
                pending.update { it - 1 }
            }
        }
    }

    private fun renderNow(context: Context) {
        val snapshot = LauncherStore(context).read()
        val hidden = LauncherPrivacy.hidden(context)
        DeviceShortcuts.sync(context, snapshot, hidden)
        val signature = widgetSignature(context, snapshot, hidden)
        if (signature != lastWidgetSignature) {
            lastWidgetSignature = signature
            FavoritesWidget.updateAll(context)
        }
    }

    /** Avoid repeated RemoteViews writes while still following visible data and the hint flag. */
    private fun widgetSignature(context: Context, snapshot: LauncherSnapshot?, hidden: Boolean): String {
        if (hidden) return "hidden"
        val builder = StringBuilder()
        val needsApp = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE)
            .getBoolean("needs_app", false)
        builder.append(needsApp).append('|')
        if (snapshot == null) return builder.append("empty").toString()
        builder.append(snapshot.profileId).append('|').append(snapshot.updatedAt)
        snapshot.devices.forEach { device ->
            if (device.favorite) {
                builder.append('|').append(device.token).append(':').append(device.name)
                    .append(':').append(device.online)
            }
        }
        return builder.toString()
    }
}
