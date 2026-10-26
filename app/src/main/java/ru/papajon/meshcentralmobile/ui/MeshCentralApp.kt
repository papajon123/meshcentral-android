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
    LaunchedEffect(workspace.showTransfersRequested) {
        if (workspace.showTransfersRequested) { managingTransfers = true; workspace.showTransfersRequested = false }
    }
    val recentStore = remember(activeProfileId) { RecentDevicesStore(context, activeProfileId) }
    val recentScope = "$activeProfileId|${CredentialScope.normalize(settings.serverUrl)}|${clientState.userId.orEmpty()}"
    var recentIds by remember(recentScope) { mutableStateOf(recentStore.ids(recentScope)) }
    fun recordRecent(device: ru.papajon.meshcentralmobile.mesh.MeshDevice) {
        recentStore.record(recentScope, device.nodeId)
        recentIds = recentStore.ids(recentScope)
    }
    var pendingFileClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var browserUnavailable by remember { mutableStateOf(false) }
    val fileState = sessionViewModel.filesController?.state?.collectAsState()?.value
    val fileOperationBusy = workspace.transfers.hasPending || workspace.sessions.any { it.files?.state?.value?.busy == true }
    LaunchedEffect(fileState?.transferName) {
        if (fileState?.transferName != null && fileState.busy) {
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
    fun activateProfile(id: String) {
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
        settings.trustSelfSignedCertificates, activeProfile.pinnedSpkiSha256) {
        if (serverUrlIsValid) {
            meshClient.configureServer(settings.serverUrl)
            meshClient.setAllowSelfSignedCertificates(settings.trustSelfSignedCertificates)
            meshClient.setPinnedServerKey(activeProfile.pinnedSpkiSha256)
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
            meshClient.setPinnedServerKey(activeProfile.pinnedSpkiSha256)
            meshClient.loginWithStoredCredentials(settings.serverUrl)
        }
    }

    DisposableEffect(meshClient) {
        // The client lives in a ViewModel: only stop the network work here, do not close it on
        // recomposition or rotation.
        onDispose { }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
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
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A successful sign-in returns the user to the computer list.
    LaunchedEffect(clientState.socketConnected) {
        if (clientState.socketConnected) loginDismissed = false
    }

    if (appLockController.isLocked) {
        LockScreen(
            biometricEnabled = settings.biometricUnlockEnabled,
            onPinSubmit = appLockController::unlockWithPin,
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
    if (updateState.needsConsent) UpdateConsentDialog(updateModel::consent)

    LaunchedEffect(workspace.eventDestination, activeProfileId, clientState.devicesCurrent, clientState.userId) {
        val target = workspace.eventDestination ?: return@LaunchedEffect
        if (target.profileId != activeProfileId) { activateProfile(target.profileId); return@LaunchedEffect }
        if (target.nodeId == null) { managingNotifications = true; workspace.eventDestination = null; return@LaunchedEffect }
        if (!clientState.devicesCurrent || clientState.requiresSignIn) { loginDismissed = false; return@LaunchedEffect }
        val device = clientState.devices.firstOrNull { it.nodeId == target.nodeId }
        if (device != null) {
            sessionViewModel.openDevice(device)
            sessionViewModel.selectDeviceTab(DeviceTab.Help)
            currentDestination = AppDestination.Devices
            target.jobId?.let { jobId ->
                val companion = workspace.companion(device.nodeId)
                companion.refresh().join()
                companion.selectJob(jobId)
            }
        }
        workspace.eventDestination = null
    }

    if (managingProfiles) ServerProfilesSheet(
        profiles = profiles,
        activeProfileId = activeProfileId,
        onActivate = ::activateProfile,
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
            onContinueWithoutSignIn = { loginDismissed = true },
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
                certificateApproval.dismiss()
                meshClient.dismissCertificateFailure()
            },
        ) }
        return
    }

    pendingFileClose?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingFileClose = null },
            title = { Text(stringResource(R.string.device_close_transfer_title)) },
            text = { Text(stringResource(R.string.device_close_transfer_message)) },
            confirmButton = { TextButton(onClick = { pendingFileClose = null; action() }) {
                Text(stringResource(R.string.device_close_session))
            } },
            dismissButton = { TextButton(onClick = { pendingFileClose = null; workspace.eventDestination = null }) { Text(stringResource(R.string.cancel)) } },
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
                    TextButton(onClick = { managingProfiles = true }, modifier = Modifier.weight(1f)) { Text(settingsRepository.activeProfile.name) }
                    TextButton(onClick = { managingSessions = true }) { Text(stringResource(R.string.workspace_sessions_count, workspace.sessions.size)) }
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
                        if (device == null) {
                            val inspector = remember(meshClient, sessionViewModel.selectedDeviceId) {
                                workspace.inspector(requireNotNull(sessionViewModel.selectedDeviceId))
                            }
                            val info by inspector.state.collectAsState()
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.device_missing))
                                info.notice?.let { Text(it.asString()) }
                                info.error?.let { Text(it.asString()) }
                                TextButton(onClick = { sessionViewModel.browseDevices() }) {
                                    Text(stringResource(R.string.device_back))
                                }
                            }
                        } else DeviceScreen(
                            device = device,
                            server = clientState.serverCapabilities,
                            current = clientState.socketConnected && clientState.devicesCurrent,
                            selected = sessionViewModel.deviceTab,
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
                                        snapshot = info.snapshot ?: ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot(deviceName = device.name, osName = device.osDescription.takeIf { it.isNotBlank() }),
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
                    )
                } }
            }
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
