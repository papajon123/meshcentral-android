package ru.papajon.meshcentralmobile.notifications

import ru.papajon.meshcentralmobile.json.optText

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/**
 * UI-facing controller for one profile.
 *
 * [enable] is the only path that registers a token, and it requires an authenticated websocket
 * plus the caller's explicit opt-in. [disable] always cancels WorkManager and clears the vault,
 * even when the online revoke fails. The token is never exposed in [state].
 */
class NotificationController(
    context: Context,
    val profileId: String,
    private val serverUrl: String,
    userId: String,
    private val client: MeshCentralClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val vault = SecureVault(appContext, profileId)
    private val registration = NotificationRegistrationClient(client)
    private var effectiveUserId = userId
    private var transientError: NotificationError? = null

    private val _state = MutableStateFlow(NotificationUiState())
    private val _feed = MutableStateFlow<List<NotificationFeedItem>>(emptyList())
    val state: StateFlow<NotificationUiState> = _state.asStateFlow()
    val feed: StateFlow<List<NotificationFeedItem>> = _feed.asStateFlow()

    init {
        NotificationNotifier.ensureChannel(appContext)
        refresh()
    }

    fun refresh() {
        val profile = SettingsRepository(appContext).profilesState.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            publish(
                NotificationUiState(profileAvailable = false, lastError = transientError),
                emptyList(),
            )
            return
        }
        val now = System.currentTimeMillis()
        if (effectiveUserId.isBlank()) effectiveUserId = client.clientState.value.userId.orEmpty()
        val raw = runCatching {
            vault.getScopedSecret(NotificationContract.LABEL, profile.serverUrl)
        }.getOrNull()
        var token = raw?.let(NotificationCodec::decodeToken)
        if (raw != null && token == null) {
            runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
            NotificationWorkScheduler.cancel(appContext, profileId)
        }
        if (token != null && effectiveUserId.isBlank()) effectiveUserId = token.userId
        if (token != null && effectiveUserId.isNotBlank() && token.userId != effectiveUserId) {
            disableLocal(appContext, profileId)
            publish(
                NotificationUiState(profileAvailable = true, lastError = NotificationError.ACCOUNT_MISMATCH),
                emptyList(),
            )
            return
        }

        val support = NotificationTransport.support(appContext, profileId, profile.serverUrl)
        val unsupported = (support as? NotificationTransportSupport.Unsupported)?.reason
        val storeUser = token?.userId ?: effectiveUserId.ifBlank {
            NotificationStore.lastUserId(appContext, profileId).orEmpty()
        }
        val store = if (storeUser.isNotBlank()) {
            NotificationStore.open(appContext, profileId, profile.serverUrl, storeUser)
        } else {
            null
        }
        var enabled = store?.isEnabled() == true
        var lastError = store?.lastError() ?: transientError
        var tokenExpiresAt = token?.expiresAt
        if (enabled && token == null) {
            store?.setEnabled(false)
            store?.markError(NotificationError.NOT_ENABLED, now)
            NotificationWorkScheduler.cancel(appContext, profileId)
            enabled = false
            lastError = NotificationError.NOT_ENABLED
        }
        if (token != null && token.expiresAt <= now) {
            runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
            NotificationWorkScheduler.cancel(appContext, profileId)
            store?.setEnabled(false)
            store?.markError(NotificationError.TOKEN_EXPIRED, now)
            token = null
            tokenExpiresAt = null
            enabled = false
            lastError = NotificationError.TOKEN_EXPIRED
        }
        if (unsupported != null) {
            lastError = unsupported
            if (unsupported == NotificationError.SELF_SIGNED_UNSUPPORTED ||
                unsupported == NotificationError.INSECURE_TRANSPORT
            ) {
                enabled = false
                runCatching { NotificationWorkScheduler.cancel(appContext, profileId) }
            }
        }
        val settings = store?.settings() ?: NotificationSettings()
        val items = store?.feed().orEmpty().filter { it.kind in settings.kinds }
        publish(
            NotificationUiState(
                enabled = enabled && token != null && unsupported == null,
                permissionGranted = NotificationNotifier.hasPermission(appContext),
                backgroundSupported = unsupported == null,
                unsupportedReason = unsupported,
                profileAvailable = true,
                tokenExpiresAt = tokenExpiresAt,
                lastCheckAt = store?.lastCheckAt(),
                lastError = lastError,
                busy = _state.value.busy,
                settings = settings,
                feed = items,
            ),
            items,
        )
    }

    /** Must be called only after the explicit opt-in and the notification permission flow. */
    suspend fun enable(): Boolean {
        if (_state.value.busy) return false
        updateBusy(true)
        return try {
            NotificationLocks.forProfile(profileId).withLock { enableLocked() }
        } finally {
            updateBusy(false)
            refresh()
        }
    }

    private suspend fun enableLocked(): Boolean {
        val profile = SettingsRepository(appContext).profilesState.value.firstOrNull { it.id == profileId }
            if (profile == null) {
                setError(NotificationError.PROFILE_MISSING)
                return false
            }
            val support = NotificationTransport.support(appContext, profileId, profile.serverUrl)
            if (support is NotificationTransportSupport.Unsupported) {
                setError(support.reason)
                return false
            }
            val user = effectiveUserId.ifBlank { client.clientState.value.userId.orEmpty() }
            if (user.isBlank()) {
                setError(NotificationError.NOT_SIGNED_IN)
                return false
            }
            if (client.clientState.value.userId != null && client.clientState.value.userId != user) {
                setError(NotificationError.ACCOUNT_MISMATCH)
                return false
            }
            effectiveUserId = user
            val now = System.currentTimeMillis()
            val existing = runCatching {
                vault.getScopedSecret(NotificationContract.LABEL, profile.serverUrl)
            }.getOrNull()?.let(NotificationCodec::decodeToken)
            if (existing != null) {
                if (existing.expiresAt > now) {
                    try {
                        registration.revoke(existing.tokenId)
                    } catch (error: NotificationPluginException) {
                        if (error.code != "NOTIFICATION_NOT_FOUND") {
                            setError(mapPluginError(error.code))
                            return false
                        }
                    } catch (error: ControlRequestException) {
                        setError(mapControlError(error.code))
                        return false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        setError(NotificationError.NETWORK)
                        return false
                    }
                }
                runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
            }
            val data = try {
                registration.register()
            } catch (error: NotificationPluginException) {
                setError(mapPluginError(error.code))
                return false
            } catch (error: ControlRequestException) {
                setError(mapControlError(error.code))
                return false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setError(NotificationError.NETWORK)
                return false
            }
            val token = try {
                NotificationRegistrationParser.parse(data, profile.serverUrl, user, now)
            } catch (_: NotificationProtocolException) {
                data.optText("tokenId")
                    .takeIf { it.matches(Regex("mcn_[0-9a-f]{16}")) }
                    ?.let { revokeQuietly(it) }
                setError(NotificationError.PROTOCOL)
                return false
            }
            val encoded = NotificationCodec.encodeToken(token)
            if (encoded == null) {
                revokeQuietly(token.tokenId)
                setError(NotificationError.PROTOCOL)
                return false
            }
            try {
                vault.saveScopedSecret(NotificationContract.LABEL, profile.serverUrl, encoded)
            } catch (_: Exception) {
                revokeQuietly(token.tokenId)
                setError(NotificationError.UNKNOWN)
                return false
            }
            val store = NotificationStore.open(appContext, profileId, profile.serverUrl, user)
            if (store == null) {
                revokeQuietly(token.tokenId)
                runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
                setError(NotificationError.INVALID_ENDPOINT)
                return false
            }
            store.setEnabled(true)
            store.clearStatus()
            try {
                NotificationWorkScheduler.schedule(appContext, profileId)
            } catch (_: Exception) {
                store.setEnabled(false)
                runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
                revokeQuietly(token.tokenId)
                setError(NotificationError.UNKNOWN)
                return false
            }
        transientError = null
        return true
    }

    /** Revokes online when possible, then always cancels work, clears the token and disables. */
    suspend fun disable() {
        updateBusy(true)
        try {
            NotificationLocks.forProfile(profileId).withLock { disableLocked() }
        } finally {
            updateBusy(false)
            refresh()
        }
    }

    private suspend fun disableLocked() {
        val currentServerUrl = SettingsRepository(appContext).profilesState.value
            .firstOrNull { it.id == profileId }?.serverUrl ?: serverUrl
        val token = runCatching {
            vault.getScopedSecret(NotificationContract.LABEL, currentServerUrl)
        }.getOrNull()?.let(NotificationCodec::decodeToken)
        try {
            if (token != null && client.clientState.value.socketConnected) {
                try {
                    registration.revoke(token.tokenId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Cleanup below is unconditional; a revoke failure is a known limitation.
                }
            }
        } finally {
            runCatching { NotificationWorkScheduler.cancel(appContext, profileId) }
            runCatching { vault.clearScopedSecret(NotificationContract.LABEL) }
            val user = token?.userId ?: effectiveUserId.ifBlank {
                NotificationStore.lastUserId(appContext, profileId).orEmpty()
            }
            if (user.isNotBlank()) {
                runCatching {
                    val profile = SettingsRepository(appContext).profilesState.value
                        .firstOrNull { it.id == profileId } ?: return@runCatching
                    NotificationStore.open(appContext, profileId, profile.serverUrl, user)?.apply {
                        setEnabled(false)
                        clearStatus()
                    }
                }
            }
            transientError = null
        }
    }

    suspend fun checkNow(): Boolean {
        if (_state.value.busy) return false
        if (!_state.value.enabled) {
            setError(NotificationError.NOT_ENABLED)
            return false
        }
        updateBusy(true)
        return try {
            val result = try {
                NotificationPoller(appContext, profileId).poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationPollResult.RETRY
            }
            val success = result == NotificationPollResult.SUCCESS
            if (success) transientError = null
            success
        } finally {
            updateBusy(false)
            refresh()
        }
    }

    fun updateFilters(kinds: Set<NotificationEventKind>) {
        val store = currentStore() ?: return
        store.saveSettings(store.settings().copy(kinds = kinds.toSet()))
        refresh()
    }

    fun updateQuietHours(enabled: Boolean, startHour: Int, endHour: Int): Boolean {
        if (!NotificationQuietHours.isValid(startHour, endHour)) return false
        val store = currentStore() ?: return false
        store.saveSettings(
            store.settings().copy(
                quietHours = QuietHours(enabled = enabled, startHour = startHour, endHour = endHour),
            ),
        )
        refresh()
        return true
    }

    fun clearHistory() {
        currentStore()?.clearFeed()
        refresh()
    }

    fun enableAsync(): Job = scope.launch { enable() }

    fun disableAsync(): Job = scope.launch { disable() }

    fun checkNowAsync(): Job = scope.launch { checkNow() }

    private fun currentStore(): NotificationStore? {
        val user = effectiveUserId.ifBlank { client.clientState.value.userId.orEmpty() }
        if (user.isBlank()) return null
        val profile = SettingsRepository(appContext).profilesState.value.firstOrNull { it.id == profileId }
            ?: return null
        return NotificationStore.open(appContext, profileId, profile.serverUrl, user)
    }

    private fun publish(state: NotificationUiState, feed: List<NotificationFeedItem>) {
        _state.value = state
        _feed.value = feed
    }

    private fun updateBusy(busy: Boolean) {
        _state.update { it.copy(busy = busy) }
    }

    private fun setError(error: NotificationError) {
        transientError = error
        _state.update { it.copy(lastError = error) }
    }

    private suspend fun revokeQuietly(tokenId: String) {
        try {
            registration.revoke(tokenId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best-effort cleanup after a local failure; the server token expires on its own.
        }
    }

    private fun mapPluginError(code: String): NotificationError = when (code) {
        "TOKEN_QUOTA_EXCEEDED", "FORBIDDEN", "UNKNOWN_OP" -> NotificationError.PROTOCOL
        "NOTIFICATION_NOT_FOUND" -> NotificationError.TOKEN_EXPIRED
        else -> NotificationError.HTTP
    }

    private fun mapControlError(code: String): NotificationError = when (code) {
        "not_authenticated", "disconnected", "send_failed" -> NotificationError.NOT_SIGNED_IN
        "too_many_requests" -> NotificationError.RATE_LIMITED
        else -> NotificationError.NETWORK
    }

    companion object {
        /** Delete/logout/URL-change cleanup. It clears every scoped notification record. */
        @JvmStatic
        fun disableLocal(context: Context, profileId: String) {
            runCatching { NotificationWorkScheduler.cancel(context.applicationContext, profileId) }
            runCatching {
                SecureVault(context.applicationContext, profileId)
                    .clearScopedSecret(NotificationContract.LABEL)
            }
            NotificationStore.clearProfile(context.applicationContext, profileId)
        }
    }
}
