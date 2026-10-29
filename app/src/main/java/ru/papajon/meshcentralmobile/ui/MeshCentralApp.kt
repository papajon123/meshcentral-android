package ru.papajon.meshcentralmobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.AppDestination
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.AppLockController
import ru.papajon.meshcentralmobile.settings.ServerCertificateApproval
import ru.papajon.meshcentralmobile.ui.screens.ForgetServerKeyDialog
import ru.papajon.meshcentralmobile.settings.ServerUrlValidator
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.components.MeshNavigationRail
import ru.papajon.meshcentralmobile.ui.components.MeshNavigationBar
import ru.papajon.meshcentralmobile.ui.screens.DevicesScreen
import ru.papajon.meshcentralmobile.ui.screens.DeviceScreen
import ru.papajon.meshcentralmobile.ui.screens.TerminalScreen
import ru.papajon.meshcentralmobile.ui.screens.FilesScreen
import ru.papajon.meshcentralmobile.ui.screens.LoginScreen
import ru.papajon.meshcentralmobile.ui.screens.LockScreen
import ru.papajon.meshcentralmobile.ui.screens.OnboardingScreen
import ru.papajon.meshcentralmobile.ui.screens.RemoteDesktopScreen
import ru.papajon.meshcentralmobile.ui.screens.ServerSettingsScreen
import ru.papajon.meshcentralmobile.ui.screens.SettingsScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.screens.profiles.ServerProfilesSheet
import ru.papajon.meshcentralmobile.discovery.RecentDevicesStore
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.security.CredentialScope
import androidx.compose.runtime.key
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.ui.components.SessionWorkspaceSheet
import ru.papajon.meshcentralmobile.management.DeviceInspectorController
import ru.papajon.meshcentralmobile.ui.screens.management.DeviceInspectorScreen
import ru.papajon.meshcentralmobile.ui.screens.companion.CompanionScreen
import ru.papajon.meshcentralmobile.ui.screens.transfers.TransferQueueSheet
import ru.papajon.meshcentralmobile.ui.components.ShareDestinationPanel
import ru.papajon.meshcentralmobile.background.FileTransferService
import androidx.core.content.ContextCompat
import ru.papajon.meshcentralmobile.notifications.NotificationController
import ru.papajon.meshcentralmobile.ui.screens.notifications.NotificationSettingsSheet
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.update.AppUpdateViewModel
import ru.papajon.meshcentralmobile.ui.components.UpdateConsentDialog
import ru.papajon.meshcentralmobile.ui.components.UpdateLifecycle
import ru.papajon.meshcentralmobile.ui.components.UpdateNotice
import ru.papajon.meshcentralmobile.ui.components.UpdateSettings

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MeshCentralApp(
    settingsRepository: SettingsRepository,
    secureVault: SecureVault,
    appLockController: AppLockController,
    onScreenshotBlockingChanged: (Boolean) -> Unit,
    onBiometricUnlockRequested: ((Boolean, String?) -> Unit) -> Unit,
    onRemoteFullscreenChanged: (Boolean) -> Unit,
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.Devices) }
    var remoteFullscreen by rememberSaveable { mutableStateOf(false) }
    var loginDismissed by rememberSaveable { mutableStateOf(false) }
    var editingServer by rememberSaveable { mutableStateOf(false) }
    var managingProfiles by rememberSaveable { mutableStateOf(false) }
    var managingSessions by rememberSaveable { mutableStateOf(false) }
    var managingTransfers by rememberSaveable { mutableStateOf(false) }
    var managingNotifications by rememberSaveable { mutableStateOf(false) }
    var managingBackup by rememberSaveable { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<ru.papajon.meshcentralmobile.localization.UiText?>(null) }
    val settings by settingsRepository.settingsState
    val favorites by settingsRepository.favoritesState
    val profiles by settingsRepository.profilesState
    val activeProfileId by settingsRepository.activeProfileIdState
    val sessionViewModel: MeshSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val meshClient = sessionViewModel.client
    val activeProfile = profiles.first { it.id == activeProfileId }
    val certificateApproval = remember(meshClient, activeProfileId, settings.serverUrl) {
        ServerCertificateApproval(settingsRepository, appLockController)
    }
    val workspace = sessionViewModel.workspace
    if (workspace.clearingSession) {
        // Keep every sign-in/profile action inaccessible until persisted cleanup has completed.
        // The state/job belongs to the workspace and survives Activity recreation.
        BackHandler { }
        AlertDialog(onDismissRequest = {}, text = {
            Text(stringResource(R.string.reliability_clearing_session))
        }, confirmButton = {})
        return
    }
    LaunchedEffect(workspace.selectedDeviceId, workspace.deviceTab) { sessionViewModel.rememberSelection() }
    val transfers by workspace.transfers.state.collectAsState()
    val profileVault = sessionViewModel.credentialVault
    val clientState by meshClient.clientState.collectAsState()
    val context = LocalContext.current
    val forwardNotificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* Denying notifications does not revoke a user-started foreground connection. */ }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val updateModel: AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updateState by updateModel.state.collectAsState()
    val notificationController = remember(activeProfileId, settings.serverUrl, clientState.userId, meshClient) {
        NotificationController(context, activeProfileId, settings.serverUrl, clientState.userId.orEmpty(), meshClient, workspace.scope)
    }
    fun reopenSelectedTool() {
        val tab = sessionViewModel.deviceTab
        workspace.selectedSession?.let { sessionViewModel.closeSession(it.id) }
        sessionViewModel.selectDeviceTab(tab)
    }
    LaunchedEffect(workspace.transfers, transfers.any { it.status == ru.papajon.meshcentralmobile.transfer.TransferStatus.Queued || it.status == ru.papajon.meshcentralmobile.transfer.TransferStatus.Active }) {
        if (workspace.transfers.hasPending) {
            try { ContextCompat.startForegroundService(context, Intent(context, FileTransferService::class.java)) }
            catch (_: Exception) {
                workspace.transfers.cancelAll()
                workspace.message = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_background_unavailable)
            }
        }
    }
    LaunchedEffect(workspace.showChatsRequested) {
        if (workspace.showChatsRequested) { managingSessions = true; workspace.showChatsRequested = false }
    }
    LaunchedEffect(workspace.showTransfersRequested) {
        if (workspace.showTransfersRequested) { managingTransfers = true; workspace.showTransfersRequested = false }
    }
    val recentStore = remember(activeProfileId) { RecentDevicesStore(context, activeProfileId) }
    val recentScope = "$activeProfileId|${CredentialScope.normalize(settings.serverUrl)}|${clientState.userId.orEmpty()}"
    var recentIds by remember(recentScope) { mutableStateOf(recentStore.ids(recentScope)) }
    val devicesObservedAt = remember(meshClient, clientState.devices, clientState.devicesCurrent) { System.currentTimeMillis() }
    fun recordRecent(device: ru.papajon.meshcentralmobile.mesh.MeshDevice) {
        recentStore.record(recentScope, device.nodeId)
        recentIds = recentStore.ids(recentScope)
    }
    LaunchedEffect(activeProfileId, settings.serverUrl, clientState.userId, clientState.devices,
        clientState.devicesCurrent, clientState.socketConnected, favorites, recentIds) {
        val user = clientState.userId
        if (user != null && clientState.socketConnected && clientState.devicesCurrent && !clientState.requiresSignIn) {
            kotlinx.coroutines.delay(250)
            ru.papajon.meshcentralmobile.integration.LauncherCoordinator.publish(context,
                activeProfileId,
                ru.papajon.meshcentralmobile.integration.LauncherStore.scope(activeProfileId, settings.serverUrl, user),
                clientState.devices, favorites.map { it.url }.toSet(), recentIds, devicesObservedAt,
                canPublish = { !workspace.clearingSession && workspace.client === meshClient && workspace.profileId == activeProfileId &&
                    meshClient.clientState.value.userId == user && meshClient.clientState.value.devicesCurrent &&
                    CredentialScope.matches(meshClient.configuredServerAddress.orEmpty(), settings.serverUrl) },
            ).join()
        }
    }
    LaunchedEffect(meshClient, clientState.socketConnected, clientState.devicesCurrent, clientState.userId, appLockController.isLocked, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !appLockController.isLocked && clientState.socketConnected && clientState.devicesCurrent &&
            clientState.userId != null && !clientState.requiresSignIn && workspace.client === meshClient &&
            workspace.profileId == activeProfile.id && CredentialScope.matches(meshClient.configuredServerAddress.orEmpty(), activeProfile.serverUrl)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Capture the authenticated profile object, never read a possibly switched
                // activeProfileId delegate after dispatching to IO.
                ru.papajon.meshcentralmobile.integration.WidgetRefreshGate(context).appAuthenticated(activeProfile.id)
            }
        }
    }
    var pendingFileClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var browserUnavailable by remember { mutableStateOf(false) }
    val fileState = sessionViewModel.filesController?.state?.collectAsState()?.value
    val fileOperationBusy = workspace.transfers.hasPending || workspace.sessions.any { it.files?.state?.value?.let { state -> state.busy || state.batchBusy } == true }
    LaunchedEffect(fileState?.transferName, fileState?.batchBusy) {
        if (fileState?.batchBusy == true || (fileState?.transferName != null && fileState.busy)) {
            try { ContextCompat.startForegroundService(context, Intent(context, FileTransferService::class.java)) }
            catch (_: Exception) {
                sessionViewModel.filesController?.cancel()
                workspace.message = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_background_unavailable)
            }
        }
    }
    fun changeDeviceScreen(action: () -> Unit) {
        if (fileOperationBusy) pendingFileClose = action else action()
    }
    fun activateProfile(id: String, fromDestination: Boolean = false) {
        if (!fromDestination) workspace.cancelPendingDestinations()
        changeDeviceScreen {
            sessionViewModel.switchProfile(id)
            settingsRepository.activateProfile(id)
            managingProfiles = false
            editingServer = false
            loginDismissed = false
            currentDestination = AppDestination.Devices
            remoteFullscreen = false
            onRemoteFullscreenChanged(false)
        }
    }
    LaunchedEffect(meshClient, settings.serverUrl, clientState.userId) {
        sessionViewModel.bindIdentity(settings.serverUrl, clientState.userId)
        pendingFileClose = null
    }
    BackHandler(enabled = currentDestination == AppDestination.Settings && !editingServer) {
        currentDestination = AppDestination.Devices
    }
    BackHandler(enabled = currentDestination == AppDestination.Devices && sessionViewModel.selectedDeviceId != null) {
        if (sessionViewModel.deviceTab == DeviceTab.Overview) sessionViewModel.browseDevices()
        else sessionViewModel.showOverview()
    }

    // An empty or invalid saved address must never be handed to the client, even if
    // a previous onboarding flag says setup has completed.
    val serverUrlIsValid = remember(settings.serverUrl, settings.allowHttpInDebug) {
        settings.serverUrl.isNotBlank() &&
            ServerUrlValidator.validate(
                settings.serverUrl,
                BuildConfig.DEBUG && settings.allowHttpInDebug,
            ) == null
    }

    LaunchedEffect(meshClient, activeProfileId, settings.serverUrl, settings.allowHttpInDebug,
        settings.trustSelfSignedCertificates, activeProfile.pinnedSpkiSha256, activeProfile.expectedSpkiSha256) {
        if (serverUrlIsValid) {
            meshClient.configureServer(settings.serverUrl)
            meshClient.setAllowSelfSignedCertificates(settings.trustSelfSignedCertificates)
            meshClient.setServerKeyPolicy(activeProfile.pinnedSpkiSha256, activeProfile.expectedSpkiSha256)
        }
    }

    // A control client should not ask for the password again on every launch: try the stored
    // credentials once, and fall back to the sign-in screen only if that fails.
    LaunchedEffect(meshClient, settings.serverUrl, settings.allowHttpInDebug, settings.onboardingCompleted) {
        if (settings.onboardingCompleted && serverUrlIsValid && !clientState.socketConnected &&
            !meshClient.certificateTrustRequired &&
            profileVault.getCredentials(settings.serverUrl) != null
        ) {
            // Install the active profile's complete trust policy before any stored-credential attempt.
            meshClient.configureServer(settings.serverUrl)
            meshClient.setAllowSelfSignedCertificates(settings.trustSelfSignedCertificates)
            meshClient.setServerKeyPolicy(activeProfile.pinnedSpkiSha256, activeProfile.expectedSpkiSha256)
            meshClient.loginWithStoredCredentials(settings.serverUrl)
        }
    }

    DisposableEffect(meshClient) {
        // The client lives in a ViewModel: only stop the network work here, do not close it on
        // recomposition or rotation.
        onDispose { }
    }

    val desktopPipHost = ru.papajon.meshcentralmobile.ui.screens.session.LocalDesktopPip.current
    LaunchedEffect(meshClient, currentDestination, appLockController.isLocked) {
        meshClient.setRemotePaused(currentDestination != AppDestination.Session || appLockController.isLocked)
    }
    DisposableEffect(lifecycleOwner, meshClient, currentDestination, appLockController.isLocked) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Stop the remote screen stream while the app is in the background.
                Lifecycle.Event.ON_STOP -> meshClient.onAppBackground()
                Lifecycle.Event.ON_START -> {
                    meshClient.onAppForeground()
                    if (currentDestination != AppDestination.Session || appLockController.isLocked) meshClient.setRemotePaused(true)
                }
                else -> Unit
            }
        }
        // MainActivity uses the process window registry; a stopped second Activity must not
        // background an already visible PiP. Standalone hosts retain the legacy lifecycle adapter.
        if (desktopPipHost == null) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A successful sign-in returns the user to the computer list.
    LaunchedEffect(clientState.socketConnected) {
        if (clientState.socketConnected) loginDismissed = false
    }

    if (managingBackup) {
        ru.papajon.meshcentralmobile.backup.BackupScreen(settingsRepository,
            onClose = { managingBackup = false },
            onPreferencesImported = {
                onScreenshotBlockingChanged(settingsRepository.getSettings().blockScreenshots)
                appLockController.refreshLockRequirement()
            }, locked = appLockController.isLocked)
        if (!appLockController.isLocked) return
    }

    if (appLockController.isLocked) {
        if (desktopPipHost?.active == true) {
            ru.papajon.meshcentralmobile.ui.screens.session.LockedDesktopPip()
            return
        }
        LockScreen(
            biometricEnabled = settings.biometricUnlockEnabled,
            pinEnabled = secureVault.hasPin(),
            pinLength = remember(secureVault) { secureVault.pinLength() },
            onPinSubmit = appLockController::unlockWithPinAsync,
            onBiometricUnlockRequested = { callback ->
                onBiometricUnlockRequested { success, error ->
                    if (success) appLockController.unlockAfterBiometric()
                    callback(success, error)
                }
            },
        )
        return
    }

    UpdateLifecycle(updateModel)
    ru.papajon.meshcentralmobile.ui.components.ArchiveCleanupNoticeDialog()
    if (updateState.needsConsent) UpdateConsentDialog(updateModel::consent)

    // Deliberately below the lock gate. Only opaque local tokens can address a cached device;
    // the current server snapshot and account must authorize it again before opening anything.
    LaunchedEffect(workspace.launcherDestination, activeProfileId, clientState.devicesCurrent, clientState.userId) {
        val token = workspace.launcherDestination ?: return@LaunchedEffect
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ru.papajon.meshcentralmobile.integration.LauncherStore(context).find(token)
        }
        // A new intent/manual cancellation may arrive before Compose cancels this IO lookup.
        if (workspace.launcherDestination != token) return@LaunchedEffect
        val entry = cached?.devices?.firstOrNull { it.token == token }
        if (cached == null || entry == null || profiles.none { it.id == cached.profileId }) {
            workspace.launcherDestination = null
            workspace.message = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.integration_unavailable)
            return@LaunchedEffect
        }
        val mayActivate = workspace.claimLauncherActivation(token)
        if (cached.profileId != activeProfileId) {
            if (mayActivate) activateProfile(cached.profileId, fromDestination = true)
            else workspace.launcherDestination = null
            return@LaunchedEffect
        }
        if (!clientState.devicesCurrent || clientState.requiresSignIn || clientState.userId == null) {
            loginDismissed = false
            return@LaunchedEffect
        }
        workspace.launcherDestination = null
        val currentScope = ru.papajon.meshcentralmobile.integration.LauncherStore.scope(activeProfileId, settings.serverUrl, clientState.userId!!)
        val device = clientState.devices.firstOrNull { it.nodeId == entry.nodeId && cached.scope == currentScope }
        if (device == null) {
            workspace.message = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.integration_unavailable)
        } else changeDeviceScreen {
            recordRecent(device)
            sessionViewModel.openDevice(device)
            sessionViewModel.showOverview()
            currentDestination = AppDestination.Devices
        }
    }

    LaunchedEffect(workspace.eventDestination, activeProfileId, clientState.devicesCurrent, clientState.userId) {
        val target = workspace.eventDestination ?: return@LaunchedEffect
        if (target.profileId != activeProfileId) { activateProfile(target.profileId, fromDestination = true); return@LaunchedEffect }
        if (target.nodeId == null) { managingNotifications = true; workspace.eventDestination = null; return@LaunchedEffect }
        if (!clientState.devicesCurrent || clientState.requiresSignIn) { loginDismissed = false; return@LaunchedEffect }
        val expectedClient = meshClient
        val device = try { expectedClient.resolveDevice(target.nodeId) }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return@LaunchedEffect }
        if (workspace.client !== expectedClient || workspace.eventDestination != target) return@LaunchedEffect
        if (device != null) {
            sessionViewModel.openDevice(device)
            if (target.chatSession != null) {
                // Notifications only reopen an existing conversation; never invite a user implicitly.
                workspace.sessions.firstOrNull { it.id == target.chatSession && it.nodeId == device.nodeId && it.tab == DeviceTab.Chat }
                    ?.let { sessionViewModel.selectSession(it.id) } ?: sessionViewModel.showOverview()
            } else sessionViewModel.selectDeviceTab(DeviceTab.Help)
            currentDestination = AppDestination.Devices
            target.jobId?.let { jobId ->
                val companion = workspace.companion(device.nodeId)
                companion.refresh().join()
                if (workspace.eventDestination != target) return@LaunchedEffect
                companion.selectJob(jobId)
            }
        }
        if (device == null) {
            workspace.selectedDeviceId = target.nodeId
            sessionViewModel.showOverview()
            currentDestination = AppDestination.Devices
        }
        if (workspace.eventDestination == target) workspace.eventDestination = null
    }

    if (managingProfiles) ServerProfilesSheet(
        profiles = profiles,
        activeProfileId = activeProfileId,
        onActivate = { activateProfile(it) },
        onCreate = { name, url ->
            val validation = ServerUrlValidator.validate(url, allowHttpInDebug = false)
            when {
                validation != null -> profileError = validation
                profiles.size >= ProfileCatalog.MAX_PROFILES -> profileError = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_profile_limit, ProfileCatalog.MAX_PROFILES)
                name.isBlank() || name.trim().length > 80 -> profileError = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_profile_error)
                else -> {
                    profileError = null
                    val profile = settingsRepository.createProfile(name, url, onboardingCompleted = true)
                    activateProfile(profile.id)
                }
            }
        },
        onRename = { id, name ->
            profileError = if (settingsRepository.renameProfile(id, name)) null else
                ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_profile_error)
        },
        onDelete = { id ->
            if (profiles.size > 1 && profiles.any { it.id == id }) changeDeviceScreen {
                if (!ru.papajon.meshcentralmobile.forward.PortForwardStore(context).deleteProfile(id)) {
                    profileError = ru.papajon.meshcentralmobile.localization.UiText.resource(R.string.workspace_profile_error)
                    return@changeDeviceScreen
                }
                workspace.cancelPendingDestinations()
                if (activeProfileId == id) {
                    sessionViewModel.switchProfile(profiles.first { it.id != id }.id)
                    loginDismissed = false
                }
                SecureVault(context, id).apply { clearCredentials(); clearLoginKey() }
                NotificationController.disableLocal(context, id)
                RecentDevicesStore(context, id).clearProfile()
                settingsRepository.removeProfile(id)
            }
        },
        onClose = { managingProfiles = false; profileError = null },
        error = profileError?.asString(),
    )
    if (managingSessions) SessionWorkspaceSheet(
        sessions = workspace.sessions,
        onSelect = { id ->
            sessionViewModel.selectSession(id)?.let { session ->
                currentDestination = if (session.tab == DeviceTab.Desktop) AppDestination.Session else AppDestination.Devices
            }
            managingSessions = false
        },
        onCloseSession = sessionViewModel::closeSession,
        onDismiss = { managingSessions = false },
    )
    if (managingTransfers) TransferQueueSheet(workspace.transfers, onClose = { managingTransfers = false }, onRetry = {
        managingTransfers = false
        currentDestination = AppDestination.Devices
        workspace.retryTransfer(it)
    })
    if (managingNotifications) NotificationSettingsSheet(notificationController, onClose = { managingNotifications = false }, onOpenEvent = {
        managingNotifications = false
        workspace.eventDestination = ru.papajon.meshcentralmobile.workspace.EventDestination(activeProfileId, it.nodeId, it.jobId)
    })
    workspace.message?.let { message ->
        val sessionsAction = (message as? ru.papajon.meshcentralmobile.localization.UiText.Resource)?.id in
            setOf(R.string.workspace_capacity, R.string.workspace_desktop_limit)
        AlertDialog(
            onDismissRequest = { workspace.message = null },
            text = { Text(message.asString()) },
            confirmButton = { TextButton(onClick = { workspace.message = null; if (sessionsAction) managingSessions = true }) {
                Text(stringResource(if (sessionsAction) R.string.workspace_sessions else android.R.string.ok))
            } },
            dismissButton = { TextButton(onClick = { workspace.message = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (!settings.onboardingCompleted || !serverUrlIsValid) {
        OnboardingScreen(
            settings = settings,
            initialLoginKey = profileVault.getLoginKey(settings.serverUrl).orEmpty(),
            onLoginKeySaved = { url, key -> profileVault.saveLoginKey(url, key) },
            // Saving the server is the single action of the onboarding flow: it also ends it.
            onServerUrlSaved = { url ->
                settingsRepository.updateServerUrl(url)
                settingsRepository.completeOnboarding()
            },
            onAllowHttpInDebugChanged = settingsRepository::updateAllowHttpInDebug,
            onTrustSelfSignedChanged = settingsRepository::updateTrustSelfSignedCertificates,
        )
        return
    }

    if (editingServer) {
        ModalBottomSheet(onDismissRequest = { editingServer = false }) {
            ServerSettingsScreen(
                settings = settings,
                initialLoginKey = profileVault.getLoginKey(settings.serverUrl).orEmpty(),
                onLoginKeySaved = { url, key ->
                    if (profileVault.getLoginKey(url).orEmpty() != key) meshClient.logout(clearStoredCredentials = false)
                    profileVault.saveLoginKey(url, key)
                },
                pinnedSpkiSha256 = activeProfile.pinnedSpkiSha256,
                onForgetServerKey = certificateApproval::requestForget,
                rememberedDeviceExpiresAt = clientState.rememberedDeviceExpiresAt,
                onForgetRememberedDevice = meshClient::forgetRememberedDevice,
                onServerUrlSaved = { url ->
                    if (CredentialScope.normalize(url) != CredentialScope.normalize(settings.serverUrl)) NotificationController.disableLocal(context, activeProfileId)
                    settingsRepository.updateServerUrl(url); editingServer = false
                },
                onAllowHttpInDebugChanged = settingsRepository::updateAllowHttpInDebug,
                onTrustSelfSignedChanged = settingsRepository::updateTrustSelfSignedCertificates,
            )
        }
    }

    if (certificateApproval.pendingForget) ForgetServerKeyDialog(
        onConfirm = {
            if (certificateApproval.confirmForget()) meshClient.setPinnedServerKey(null)
        },
        onDismiss = certificateApproval::dismiss,
    )

    if (!loginDismissed && clientState.requiresSignIn && currentDestination != AppDestination.Settings) {
        key(activeProfileId) { LoginScreen(
            meshClient = meshClient,
            secureVault = profileVault,
            serverUrl = settings.serverUrl,
            onContinueWithoutSignIn = { workspace.cancelPendingDestinations(); loginDismissed = true },
            onEditServer = { managingProfiles = true },
            onEditServerSettings = { editingServer = true },
            confirmingCertificateReplacement = certificateApproval.pendingReplacement == clientState.certificateFailure &&
                clientState.certificateFailure != null,
            onTrustCertificate = { failure ->
                // Reject a stale dialog or an attempt that changed while the app was locked.
                if (meshClient.clientState.value.certificateFailure == failure && certificateApproval.approve(failure)) {
                    meshClient.setPinnedServerKey(failure.spkiSha256)
                    meshClient.retryAfterCertificateTrust()
                }
            },
            onReplaceCertificate = { failure ->
                if (meshClient.clientState.value.certificateFailure == failure) certificateApproval.requestReplacement(failure)
            },
            onDismissCertificate = {
                workspace.cancelPendingDestinations()
                certificateApproval.dismiss()
                meshClient.dismissCertificateFailure()
            },
        ) }
        return
    }

    pendingFileClose?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingFileClose = null; workspace.cancelPendingDestinations() },
            title = { Text(stringResource(R.string.device_close_transfer_title)) },
            text = { Text(stringResource(R.string.device_close_transfer_message)) },
            confirmButton = { TextButton(onClick = { pendingFileClose = null; action() }) {
                Text(stringResource(R.string.device_close_session))
            } },
            dismissButton = { TextButton(onClick = { pendingFileClose = null; workspace.cancelPendingDestinations() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (browserUnavailable) AlertDialog(
        onDismissRequest = { browserUnavailable = false },
        text = { Text(stringResource(R.string.device_browser_unavailable)) },
        confirmButton = { TextButton(onClick = { browserUnavailable = false }) { Text(stringResource(android.R.string.ok)) } },
    )

    val showMainNavigation = currentDestination != AppDestination.Session && sessionViewModel.selectedDeviceId == null
    val useNavigationRail = LocalConfiguration.current.screenWidthDp >= 600 && showMainNavigation
    val selectDestination: (AppDestination) -> Unit = { destination ->
        if (currentDestination == AppDestination.Session && destination != AppDestination.Session) {
            remoteFullscreen = false
            onRemoteFullscreenChanged(false)
        }
        currentDestination = destination
    }
    Scaffold(
        contentWindowInsets = if (currentDestination == AppDestination.Session) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
        topBar = {
            if (currentDestination != AppDestination.Session) Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { managingProfiles = true }, modifier = Modifier.weight(1f)) {
                        Text(settingsRepository.activeProfile.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { managingSessions = true }) {
                        Text(stringResource(R.string.workspace_sessions_count, workspace.sessions.size))
                        ru.papajon.meshcentralmobile.ui.components.ChatUnreadBadge(workspace.unreadChats)
                    }
                }
                if (transfers.isNotEmpty()) TextButton(onClick = { managingTransfers = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.workspace_transfers_count, transfers.size))
                }
                if (workspace.sharedFiles.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.workspace_share_destination, workspace.sharedFiles.size), modifier = Modifier.weight(1f))
                    TextButton(onClick = workspace::dismissShare) { Text(stringResource(R.string.cancel)) }
                }
            }
        },
        bottomBar = {
            // The live session owns the whole window: its own dock and back action replace the tab
            // bar, which otherwise eats a fifth of the canvas.
            if (showMainNavigation && !useNavigationRail) {
                MeshNavigationBar(currentDestination, selectDestination)
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
        if (useNavigationRail) MeshNavigationRail(currentDestination, selectDestination)
        Crossfade(
            modifier = Modifier.weight(1f),
            targetState = currentDestination,
            animationSpec = MeshTheme.motion.effectDefault,
            label = "destination",
        ) { destination ->
            // The session canvas wants the whole window (its own chrome handles insets); the list
            // and settings screens keep the status bar clear.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .clipToBounds(),
            ) {
                key(activeProfileId) { when (destination) {
                    AppDestination.Devices -> if (sessionViewModel.selectedDeviceId == null) DevicesScreen(
                        updateBanner = updateState.candidate?.let { candidate ->
                            { UpdateNotice(candidate, updateModel::later, modifier = Modifier.padding(horizontal = MeshTheme.spacing.lg, vertical = MeshTheme.spacing.sm)) }
                        },
                        meshClient = meshClient,
                        favorites = favorites,
                        onAddFavorite = settingsRepository::addFavorite,
                        onRemoveFavorite = settingsRepository::removeFavorite,
                        onOpenRemote = {
                            meshClient.desktopState.value.device?.let(::recordRecent)
                            currentDestination = AppDestination.Session
                        },
                        onOpenSettings = { currentDestination = AppDestination.Settings },
                        onSignIn = { loginDismissed = false },
                        onOpenDevice = {
                            recordRecent(it); sessionViewModel.openDevice(it)
                            if (workspace.deviceTab == DeviceTab.Desktop && workspace.selectedSession != null) currentDestination = AppDestination.Session
                        },
                        recentDeviceIds = recentIds,
                        onClearRecents = { recentStore.clear(recentScope); recentIds = emptyList() },
                        onConnectDevice = { device ->
                            if (sessionViewModel.connectDesktop(device)) {
                                recordRecent(device)
                                currentDestination = AppDestination.Session
                            }
                        },
                    ) else {
                        val device = clientState.devices.firstOrNull { it.nodeId == sessionViewModel.selectedDeviceId }
                        var androidTasks by remember(meshClient, device?.nodeId) { mutableStateOf(false) }
                        LaunchedEffect(meshClient, device?.nodeId, device?.conn, device?.rights, clientState.devicesCurrent, clientState.socketConnected) {
                            if (device?.isAndroidAgent == true && ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.canRead(device, clientState.devicesCurrent && clientState.socketConnected)) {
                                androidTasks = try {
                                    ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.processes(meshClient.taskTools(device.nodeId).read("ps"))
                                    true
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                                    false
                                } catch (_: Exception) { false }
                            }
                        }
                        if (device == null) {
                            val inspector = remember(meshClient, sessionViewModel.selectedDeviceId) {
                                workspace.inspector(requireNotNull(sessionViewModel.selectedDeviceId))
                            }
                            val info by inspector.state.collectAsState()
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(if (!clientState.devicesCurrent || sessionViewModel.selectedDeviceId in clientState.resolvingDeviceIds)
                                    R.string.devices_syncing else R.string.device_missing))
                                info.notice?.let { Text(it.asString()) }
                                info.error?.let { Text(it.asString()) }
                                TextButton(onClick = {
                                    if (!clientState.devicesCurrent) meshClient.retryDevices()
                                    meshClient.selectDevice(sessionViewModel.selectedDeviceId)
                                }) { Text(stringResource(R.string.connection_retry)) }
                                TextButton(onClick = { sessionViewModel.browseDevices() }) {
                                    Text(stringResource(R.string.device_back))
                                }
                            }
                        } else DeviceScreen(
                            device = device,
                            server = clientState.serverCapabilities,
                            current = clientState.socketConnected && clientState.devicesCurrent,
                            selected = sessionViewModel.deviceTab,
                            taskToolsSupported = androidTasks || ru.papajon.meshcentralmobile.management.tasks.TaskToolsProtocol.visible(device),
                            hasChat = workspace.sessions.any { it.nodeId == device.nodeId && it.chat != null },
                            onSelect = { tab ->
                                if (tab == DeviceTab.Desktop) {
                                    if (sessionViewModel.connectDesktop(device)) currentDestination = AppDestination.Session
                                } else sessionViewModel.selectDeviceTab(tab)
                            },
                            onBack = sessionViewModel::browseDevices,
                            onWebPanel = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settings.serverUrl))) }
                                catch (_: ActivityNotFoundException) { browserUnavailable = true }
                            },
                        ) {
                            when (sessionViewModel.deviceTab) {
                                DeviceTab.Processes, DeviceTab.Services -> {
                                    val taskScope = rememberCoroutineScope()
                                    val services = sessionViewModel.deviceTab == DeviceTab.Services
                                    val controller = remember(meshClient, device.nodeId, services) {
                                        ru.papajon.meshcentralmobile.management.tasks.TaskToolsController(meshClient.taskTools(device.nodeId), services, taskScope)
                                    }
                                    DisposableEffect(controller) { onDispose { controller.close() } }
                                    ru.papajon.meshcentralmobile.ui.screens.management.TaskToolsScreen(controller, device,
                                        clientState.devicesCurrent && clientState.socketConnected, services, sessionViewModel::showOverview,
                                        features2 = clientState.serverCapabilities.features2,
                                        screenVisible = !managingSessions && !managingProfiles && !managingNotifications && !managingTransfers && !editingServer)
                                }
                                DeviceTab.Chat -> workspace.selectedSession?.let { session ->
                                    session.chat?.let { controller ->
                                        ru.papajon.meshcentralmobile.ui.screens.ChatScreen(controller,
                                            deviceName = session.deviceName,
                                            onBack = sessionViewModel::showOverview,
                                            onKeepAlive = workspace::ensureChatService,
                                            screenVisible = !managingSessions && !managingProfiles && !managingNotifications && !managingTransfers && !editingServer,
                                            onClose = { sessionViewModel.closeSession(session.id) },
                                            onRead = { ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.cancel(context, activeProfileId, session.id) })
                                    }
                                } ?: EndedSession { sessionViewModel.selectDeviceTab(DeviceTab.Chat) }
                                DeviceTab.PortForwarding -> {
                                    val store = remember(context) { ru.papajon.meshcentralmobile.forward.PortForwardStore(context) }
                                    ru.papajon.meshcentralmobile.forward.PortForwardScreen(
                                        activeProfileId, device.nodeId, meshClient.portForwards, store,
                                        onStart = { rule ->
                                            if (rule.profileId == activeProfileId && meshClient.portForwards.start(rule)) {
                                                try { ContextCompat.startForegroundService(context, Intent(context, ru.papajon.meshcentralmobile.background.PortForwardService::class.java)) }
                                                catch (_: Exception) { meshClient.portForwards.serviceFailed() }
                                                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                                                        android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                    forwardNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                            }
                                        }, onBack = sessionViewModel::showOverview,
                                    )
                                }
                                DeviceTab.Desktop -> EndedSession {
                                    if (sessionViewModel.connectDesktop(device)) currentDestination = AppDestination.Session
                                }
                                DeviceTab.Terminal, DeviceTab.Ssh -> sessionViewModel.terminalController?.let { controller ->
                                    TerminalScreen(controller, onBack = sessionViewModel::showOverview, onReopen = ::reopenSelectedTool,
                                        favoriteCommands = profiles.firstOrNull { it.id == activeProfileId }?.favoriteCommands.orEmpty(),
                                        onAddFavorite = { settingsRepository.addFavoriteCommand(activeProfileId, it) },
                                        onRemoveFavorite = { settingsRepository.removeFavoriteCommand(activeProfileId, it) }, profileId = activeProfileId)
                                } ?: EndedSession { sessionViewModel.selectDeviceTab(sessionViewModel.deviceTab) }
                                DeviceTab.Files, DeviceTab.Sftp -> sessionViewModel.filesController?.let { controller ->
                                    Column(Modifier.fillMaxSize()) {
                                        ShareDestinationPanel(workspace, settingsRepository.activeProfile.name, device, controller) { managingTransfers = true }
                                        FilesScreen(controller, onBack = sessionViewModel::showOverview, modifier = Modifier.weight(1f), preserveOnBack = true, onReopen = ::reopenSelectedTool)
                                    }
                                } ?: EndedSession { sessionViewModel.selectDeviceTab(sessionViewModel.deviceTab) }
                                DeviceTab.System -> {
                                    val inspector = remember(meshClient, device.nodeId) { workspace.inspector(device.nodeId) }
                                    val info by inspector.state.collectAsState()
                                    LaunchedEffect(inspector) { if (info.snapshot == null) inspector.refresh() }
                                    DeviceInspectorScreen(
                                        adminContent = { ru.papajon.meshcentralmobile.ui.screens.management.DeviceAdminActions(meshClient, device) },
                                        device = device,
                                        canSendToast = ru.papajon.meshcentralmobile.management.ToastCommand.allowed(device, clientState.socketConnected && clientState.devicesCurrent),
                                        onSendToast = inspector::sendToast,
                                        events = info.events,
                                        powerTimeline = info.powerTimeline,
                                        eventsError = info.eventsError,
                                        timelineError = info.timelineError,
                                        onLoadHistory = inspector::loadHistory,
                                        software = info.software,
                                        softwareError = info.softwareError,
                                        onLoadSoftware = inspector::loadSoftware,
                                        metrics = info.metrics,
                                        metricsError = info.metricsError,
                                        onLoadMetrics = inspector::loadMetrics,
                                        commandOutput = info.commandOutput,
                                        commandUncertain = info.commandUncertain,
                                        commandOutputTruncated = info.commandOutputTruncated,
                                        historyLoading = info.historyLoading,
                                        onRunCommand = inspector::runCommand,
                                        onRemove = inspector::remove,
                                        onOpenTerminal = { sessionViewModel.selectDeviceTab(DeviceTab.Terminal) },
                                        snapshot = info.snapshot?.copy(deviceName = device.name) ?: ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot(deviceName = device.name, osName = device.osDescription.takeIf { it.isNotBlank() }),
                                        services = info.services,
                                        processes = info.processes,
                                        loading = info.loading,
                                        error = info.error?.asString(),
                                        pendingAction = info.notice?.takeIf { info.loading }?.asString(),
                                        notice = info.notice?.takeUnless { info.loading }?.asString(),
                                        availablePowerActions = DeviceInspectorController.availablePowerActions(device, clientState.devicesCurrent),
                                        canManageServices = info.companionAvailable && device.rights?.let { it and ru.papajon.meshcentralmobile.mesh.MeshRights.REMOTE_COMMAND != 0 } == true,
                                        onRefresh = inspector::refresh,
                                        onLoadServices = inspector::loadServices,
                                        onLoadProcesses = inspector::loadProcesses,
                                        onPower = inspector::power,
                                        onServiceAction = inspector::serviceAction,
                                        onBack = sessionViewModel::showOverview,
                                        onOpenHelp = { sessionViewModel.selectDeviceTab(DeviceTab.Help) },
                                        phoneDevice = device.takeIf { it.isAndroidAgent },
                                        devicesCurrent = clientState.socketConnected && clientState.devicesCurrent,
                                        onPhoneCommand = inspector::phoneCommand,
                                    )
                                }
                                DeviceTab.Help -> {
                                    val companion = remember(meshClient, device.nodeId) { workspace.companion(device.nodeId) }
                                    CompanionScreen(companion, device.name, onBack = sessionViewModel::showOverview)
                                }
                                else -> Unit
                            }
                        }
                    }

                    AppDestination.Session -> RemoteDesktopScreen(
                        meshClient = meshClient,
                        customShortcuts = profiles.firstOrNull { it.id == activeProfileId }?.customShortcuts.orEmpty(),
                        onAddShortcut = { settingsRepository.addShortcut(activeProfileId, it) },
                        onRemoveShortcut = { settingsRepository.removeShortcut(activeProfileId, it) },
                        shortcutProfileId = activeProfileId,
                        stylusPrecisionEnabled = settings.stylusPrecisionEnabled,
                        stylusHintSeen = settings.stylusHintSeen,
                        onStylusHintSeen = settingsRepository::markStylusHintSeen,
                        onFullscreenChanged = {
                            remoteFullscreen = it
                            onRemoteFullscreenChanged(it)
                        },
                        onBrowseDevices = { sessionViewModel.browseDevices(); currentDestination = AppDestination.Devices },
                        onMinimize = { sessionViewModel.browseDevices(); currentDestination = AppDestination.Devices },
                        onCloseSession = {
                            workspace.sessions.firstOrNull { it.tab == DeviceTab.Desktop }?.let { sessionViewModel.closeSession(it.id) }
                            sessionViewModel.browseDevices()
                            currentDestination = AppDestination.Devices
                        },
                    )

                    AppDestination.Settings -> SettingsScreen(
                        updates = { UpdateSettings(updateState, updateModel::consent, { updateModel.check(manual = true) }, updateModel::later, updateModel::skip) },
                        settings = settings,
                        serverUrl = settings.serverUrl,
                        onStylusPrecisionChanged = settingsRepository::updateStylusPrecision,
                        hasPin = secureVault.hasPin(),
                        onScreenshotBlockingChanged = { enabled ->
                            settingsRepository.updateBlockScreenshots(enabled)
                            onScreenshotBlockingChanged(enabled)
                        },
                        onBiometricUnlockChanged = { enabled ->
                            settingsRepository.updateBiometricUnlock(enabled)
                            appLockController.refreshLockRequirement()
                        },
                        onLockTimeoutChanged = settingsRepository::updateLockTimeoutSeconds,
                        onHideDevicesWhenLockedChanged = settingsRepository::updateHideDevicesWhenLocked,
                        onPinSaved = { pin ->
                            secureVault.savePin(pin)
                            appLockController.lockNow()
                        },
                        onPinCleared = {
                            secureVault.clearPin()
                            appLockController.refreshLockRequirement()
                        },
                        onLockNow = appLockController::lockNow,
                        onClearSession = { workspace.clearSavedSession(notificationController::disable) },
                        activeProfileName = settingsRepository.activeProfile.name,
                        onManageProfiles = { profileError = null; managingProfiles = true },
                        onEditServer = { editingServer = true },
                        onNotifications = { managingNotifications = true },
                        forwarding = {
                            val savedRulesStore = remember(context) {
                                ru.papajon.meshcentralmobile.forward.PortForwardStore(context)
                            }
                            ru.papajon.meshcentralmobile.forward.SavedPortForwardRules(
                                profileId = activeProfileId,
                                store = savedRulesStore,
                                manager = meshClient.portForwards,
                                devices = clientState.devices,
                            )
                        },
                        onBackup = { managingBackup = true },
                    )
                } }
            }
        }
        }
        if (currentDestination == AppDestination.Session && workspace.unreadChats > 0) {
            ru.papajon.meshcentralmobile.ui.components.ChatUnreadAction(workspace.unreadChats,
                onClick = { managingSessions = true }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp, end = 16.dp))
        }
        }
    }
}

@Composable
private fun EndedSession(onReopen: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.workspace_ended))
        TextButton(onClick = onReopen) { Text(stringResource(R.string.workspace_reopen)) }
    }
}
