package ru.papajon.meshcentralmobile.integration

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshControlEvent
import ru.papajon.meshcentralmobile.mesh.MeshControlMessages
import ru.papajon.meshcentralmobile.mesh.MeshControlParser
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** One bounded refresh: reuse a live channel or close a temporary read-only client in finally. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    internal enum class RefreshOutcome { UPDATED, COOLDOWN, UNAVAILABLE, STALE }
    override suspend fun doWork(): Result {
        runRefresh(applicationContext)
        return Result.success() // Offline is visible to the user, never an automatic retry loop.
    }

    companion object {
        /**
         * Full worker body, callable from instrumentation tests. A cooldown leaves the existing
         * widget status untouched; only an actual attempt may mark the widget stale.
         */
        internal suspend fun runRefresh(context: Context): RefreshOutcome {
            var ticket: LauncherCoordinator.RefreshTicket? = null
            val outcome = try {
                withTimeout(12_000) {
                    ticket = LauncherCoordinator.beginRefresh(context.applicationContext)
                    ticket?.let { refreshOnce(context.applicationContext, it) } ?: RefreshOutcome.UNAVAILABLE
                }
            } catch (_: TimeoutCancellationException) {
                RefreshOutcome.UNAVAILABLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RefreshOutcome.UNAVAILABLE
            }
            if (outcome == RefreshOutcome.UNAVAILABLE) ticket?.let {
                LauncherCoordinator.reportRefreshFailure(context.applicationContext, it).join()
            }
            return outcome
        }

        /** Legacy boolean API used by older tests: a cooldown is reported as false (no update). */
        internal suspend fun refresh(context: Context): Boolean =
            runRefresh(context) == RefreshOutcome.UPDATED

        private suspend fun refreshOnce(context: Context, ticket: LauncherCoordinator.RefreshTicket): RefreshOutcome = withContext(Dispatchers.IO) {
            val cached = ticket.snapshot
            val settings = SettingsRepository(context)
            if (settings.activeProfileIdState.value != cached.profileId) return@withContext RefreshOutcome.STALE
            val profile = settings.currentProfile(cached.profileId) ?: return@withContext RefreshOutcome.STALE
            if (profile.expectedSpkiSha256 != null) return@withContext RefreshOutcome.UNAVAILABLE
            if (ru.papajon.meshcentralmobile.settings.ServerUrlValidator.validate(profile.serverUrl,
                    ru.papajon.meshcentralmobile.BuildConfig.DEBUG && profile.allowHttpInDebug) != null)
                return@withContext RefreshOutcome.UNAVAILABLE
            val workspace = ConnectionWorkspace.existing()
            if (workspace?.clearingSession == true) return@withContext RefreshOutcome.STALE
            val live = workspace?.client?.takeIf { workspace.profileId == profile.id && it.clientState.value.socketConnected }
            val devices = if (live != null) {
                if (!ru.papajon.meshcentralmobile.security.CredentialScope.matches(live.configuredServerAddress.orEmpty(), profile.serverUrl))
                    return@withContext RefreshOutcome.UNAVAILABLE
                val user = live.clientState.value.userId ?: return@withContext RefreshOutcome.UNAVAILABLE
                if (LauncherStore.scope(profile.id, profile.serverUrl, user) != cached.scope) return@withContext RefreshOutcome.UNAVAILABLE
                val reply = live.requestControl(MeshControlMessages.nodes(), "nodes", timeoutMillis = 6_000)
                if (workspace.clearingSession || workspace.client !== live || live.clientState.value.userId != user)
                    return@withContext RefreshOutcome.UNAVAILABLE
                (MeshControlParser.parse(reply) as? MeshControlEvent.Nodes)?.devices ?: return@withContext RefreshOutcome.UNAVAILABLE
            } else {
                if (SecureVault(context).hasPin() || settings.getSettings().biometricUnlockEnabled) return@withContext RefreshOutcome.UNAVAILABLE
                // A read-only vault prevents a late accepted login/cookie from resurrecting data
                // after concurrent logout. No 2FA prompt, retry scheduling or persistent connection.
                val vault = SecureVault(context, profile.id, readOnly = true)
                if (vault.getCredentials(profile.serverUrl) == null) return@withContext RefreshOutcome.UNAVAILABLE
                val gate = WidgetRefreshGate(context)
                when (gate.typedClaim(profile.id)) {
                    WidgetRefreshGate.Claim.ALLOWED -> Unit
                    WidgetRefreshGate.Claim.COOLDOWN -> {
                        LauncherCoordinator.reportRefreshCooldown(context, ticket, gate.remainingSeconds(profile.id)).join()
                        return@withContext RefreshOutcome.COOLDOWN
                    }
                    WidgetRefreshGate.Claim.BLOCKED, WidgetRefreshGate.Claim.STORAGE_FAILURE ->
                        return@withContext RefreshOutcome.UNAVAILABLE
                }
                val child = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val temporary = MeshCentralClient(vault, child, singleAttempt = true)
                val policy = SettingsRepository(context).observeProfile(profile.id) { current ->
                    if (current != profile) temporary.close()
                }
                try {
                    temporary.configureServer(profile.serverUrl)
                    temporary.setAllowSelfSignedCertificates(profile.trustSelfSignedCertificates)
                    temporary.setServerKeyPolicy(profile.pinnedSpkiSha256, profile.expectedSpkiSha256)
                    if (!temporary.loginWithStoredCredentials(profile.serverUrl)) return@withContext RefreshOutcome.UNAVAILABLE
                    val state = withTimeout(9_000) { temporary.clientState.first {
                        (it.devicesCurrent && it.userId != null) || it.awaitingTwoFactor || it.certificateFailure != null ||
                            it.connectionState == ru.papajon.meshcentralmobile.model.ConnectionState.Failed
                    } }
                    if (temporary.backgroundAccessRejected || state.awaitingTwoFactor || state.certificateFailure != null)
                        gate.reject(profile.id)
                    val user = state.userId ?: return@withContext RefreshOutcome.UNAVAILABLE
                    if (!state.devicesCurrent || state.requiresSignIn || LauncherStore.scope(profile.id, profile.serverUrl, user) != cached.scope)
                        return@withContext RefreshOutcome.UNAVAILABLE
                    state.devices
                } finally {
                    if (temporary.backgroundAccessRejected || temporary.clientState.value.awaitingTwoFactor ||
                        temporary.clientState.value.certificateFailure != null) gate.reject(profile.id)
                    policy.close(); temporary.close(); child.cancel()
                }
            }
            val current = SettingsRepository(context)
            if (current.activeProfileIdState.value != profile.id || current.currentProfile(profile.id) != profile)
                return@withContext RefreshOutcome.STALE
            // Catalog guard and render both run on LauncherCoordinator's serialized queue.
            if (LauncherCoordinator.refresh(context, ticket, devices)) RefreshOutcome.UPDATED
            else RefreshOutcome.STALE
        }
    }
}
