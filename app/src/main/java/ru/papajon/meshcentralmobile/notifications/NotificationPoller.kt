package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One mutex per profile prevents a one-time check and a periodic run from overlapping. */
object NotificationLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forProfile(profileId: String): Mutex = locks.computeIfAbsent(profileId) { Mutex() }
}

/**
 * Background HTTP poll. It has no websocket, no FCM and no ability to bypass sign-in: it can only
 * use the read-only bearer token that the authenticated controller stored after opt-in.
 */
class NotificationPoller(
    context: Context,
    private val profileId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val httpClient: NotificationHttpClient? = null,
    private val hasPermission: (Context) -> Boolean = NotificationNotifier::hasPermission,
    private val deliver: (Context, String, NotificationFeedItem) -> Boolean = NotificationNotifier::notifyEvent,
) {
    private val appContext = context.applicationContext

    suspend fun poll(): NotificationPollResult =
        NotificationLocks.forProfile(profileId).withLock { pollLocked() }

    private suspend fun pollLocked(): NotificationPollResult {
        val now = clock()
        val profiles = SettingsRepository(appContext)
        val profile = profiles.currentProfile(profileId)
        if (profile == null) {
            NotificationController.disableLocal(appContext, profileId)
            return NotificationPollResult.PROFILE_MISSING
        }
        val vault = SecureVault(appContext, profileId)
        val raw = try {
            vault.getScopedSecret(NotificationContract.LABEL, profile.serverUrl)
        } catch (_: Exception) {
            return NotificationPollResult.UNAVAILABLE
        }
        if (raw == null) {
            NotificationStore.lastUserId(appContext, profileId)?.let { userId ->
                NotificationStore.open(appContext, profileId, profile.serverUrl, userId)?.apply {
                    setEnabled(false)
                    markError(NotificationError.NOT_ENABLED, now)
                }
            }
            NotificationWorkScheduler.cancel(appContext, profileId)
            return NotificationPollResult.DISABLED
        }
        val token = NotificationCodec.decodeToken(raw)
        if (token == null) {
            runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
            NotificationWorkScheduler.cancel(appContext, profileId)
            NotificationStore.lastUserId(appContext, profileId)?.let { userId ->
                NotificationStore.open(appContext, profileId, profile.serverUrl, userId)?.apply {
                    setEnabled(false)
                    markError(NotificationError.TOKEN_EXPIRED, now)
                }
            }
            return NotificationPollResult.TOKEN_EXPIRED
        }
        val store = NotificationStore.open(appContext, profileId, profile.serverUrl, token.userId)
            ?: return NotificationPollResult.UNAVAILABLE
        if (!store.isEnabled()) return NotificationPollResult.DISABLED
        if (token.expiresAt <= now) {
            expireToken(store, vault, now)
            return NotificationPollResult.TOKEN_EXPIRED
        }
        val support = NotificationTransport.support(profile)
        when (support) {
            is NotificationTransportSupport.Unsupported -> {
                store.setEnabled(false)
                NotificationWorkScheduler.cancel(appContext, profileId)
                store.markError(support.reason, now)
                return NotificationPollResult.UNAVAILABLE
            }
            is NotificationTransportSupport.Supported -> Unit
        }
        val endpoint = NotificationEndpoint.resolve(profile.serverUrl, token.endpoint)
        if (endpoint == null) {
            store.markError(NotificationError.INVALID_ENDPOINT, now)
            return NotificationPollResult.UNAVAILABLE
        }
        val settings = store.settings()
        val policyInvalidated = AtomicBoolean(false)
        fun matchesPolicy(current: ru.papajon.meshcentralmobile.model.ServerProfile?) = current != null &&
            current.serverUrl == profile.serverUrl && current.pinnedSpkiSha256 == profile.pinnedSpkiSha256
        fun isCurrentPolicy() = !policyInvalidated.get() && matchesPolicy(profiles.currentProfile(profileId))
        val transport = httpClient ?: NotificationHttpClient.forServer(support.server, support.pinnedSpkiSha256,
            isCurrentPolicy = ::isCurrentPolicy)
        val observation = profiles.observeProfile(profileId) { current ->
            if (!matchesPolicy(current)) {
                policyInvalidated.set(true)
                transport.cancel()
            }
        }
        val page = try {
            // Subscribe first, then re-read: policy changes during client construction cannot hide.
            if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
            transport.fetch(endpoint, token.token, store.cursor())
        } catch (error: NotificationHttpException) {
            if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
            return when (error.kind) {
                NotificationHttpException.Kind.UNAUTHORIZED -> {
                    expireToken(store, vault, now)
                    NotificationPollResult.TOKEN_EXPIRED
                }
                NotificationHttpException.Kind.NETWORK -> {
                    store.markError(NotificationError.NETWORK, now)
                    NotificationPollResult.RETRY
                }
                NotificationHttpException.Kind.RATE_LIMITED -> {
                    store.markError(NotificationError.RATE_LIMITED, now)
                    NotificationPollResult.RETRY
                }
                NotificationHttpException.Kind.REDIRECT,
                NotificationHttpException.Kind.INVALID_CONTENT_TYPE,
                NotificationHttpException.Kind.EMPTY_BODY,
                NotificationHttpException.Kind.TOO_LARGE,
                -> {
                    store.markError(NotificationError.PROTOCOL, now)
                    NotificationPollResult.RETRY
                }
                NotificationHttpException.Kind.HTTP_ERROR -> {
                    store.markError(NotificationError.HTTP, now)
                    NotificationPollResult.RETRY
                }
            }
        } catch (_: SSLException) {
            if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
            store.setEnabled(false)
            NotificationWorkScheduler.cancel(appContext, profileId)
            store.markError(NotificationError.TLS_UNTRUSTED, now)
            return NotificationPollResult.UNAVAILABLE
        } catch (_: NotificationProtocolException) {
            if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
            store.markError(NotificationError.PROTOCOL, now)
            return NotificationPollResult.RETRY
        } catch (_: IOException) {
            if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
            store.markError(NotificationError.NETWORK, now)
            return NotificationPollResult.RETRY
        } finally {
            observation.close()
            if (httpClient == null) transport.close()
        }

        // A disable/revoke may have happened while the HTTP call was in flight.
        if (!store.isEnabled()) return NotificationPollResult.DISABLED
        if (!isCurrentPolicy()) return NotificationPollResult.DISABLED
        val currentRaw = runCatching {
            vault.getScopedSecret(NotificationContract.LABEL, profile.serverUrl)
        }.getOrNull()
        if (currentRaw != raw) return NotificationPollResult.DISABLED

        val feedBefore = if (page.resetCursor) emptyList() else store.feed()
        val pendingBefore = if (page.resetCursor) emptySet() else store.pending()
        // All allowlisted kinds are recorded; the user filter controls notifications and the
        // visible feed, so re-enabling a kind can reveal older events without a server refetch.
        val merged = NotificationEventPolicy.merge(feedBefore, page.items, now)
        val enabledAdded = merged.added.filter { it.kind in settings.kinds }
        val quiet = NotificationQuietHours.isQuiet(settings.quietHours, now)
        val permission = hasPermission(appContext)
        // Persist delivery intent before advancing the cursor. A process death can then retry the
        // same stable notification id without losing the event or stacking duplicate notifications.
        val pending = NotificationEventPolicy.prunePending(
            merged.feed, pendingBefore + enabledAdded.map { it.id }, now, settings.kinds,
        )
        val toNotify = if (!quiet && permission) {
            NotificationEventPolicy.pendingItems(merged.feed, pending, now, settings.kinds)
        } else {
            emptyList()
        }
        if (!store.commitCheck(merged.feed, pending, page.cursor, now)) {
            store.markError(NotificationError.PROTOCOL, now)
            return NotificationPollResult.RETRY
        }
        if (toNotify.isNotEmpty()) {
            val failed = toNotify.asSequence()
                .filterNot { item ->
                    store.isEnabled() && runCatching { deliver(appContext, profileId, item) }
                        .getOrDefault(false)
                }
                .map { it.id }
                .toSet()
            store.savePending(NotificationEventPolicy.prunePending(merged.feed, failed, now, settings.kinds))
        }
        return NotificationPollResult.SUCCESS
    }

    private fun expireToken(store: NotificationStore, vault: SecureVault, now: Long) {
        runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
        NotificationWorkScheduler.cancel(appContext, profileId)
        store.setEnabled(false)
        store.markError(NotificationError.TOKEN_EXPIRED, now)
    }
}
