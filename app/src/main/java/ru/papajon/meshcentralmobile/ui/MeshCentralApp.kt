package ru.papajon.meshcentralmobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.ui.Modifier
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
import ru.papajon.meshcentralmobile.settings.ServerUrlValidator
import ru.papajon.meshcentralmobile.settings.SettingsRepository
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
import ru.papajon.meshcentralmobile.web.MeshWebViewController
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

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
    val settings by settingsRepository.settingsState
    val favorites by settingsRepository.favoritesState
    val sessionViewModel: MeshSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val meshClient = sessionViewModel.client
    val clientState by meshClient.clientState.collectAsState()
    val context = LocalContext.current
    var pendingFileClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var browserUnavailable by remember { mutableStateOf(false) }
    val fileOperationBusy = sessionViewModel.filesController?.state?.collectAsState()?.value?.busy == true
    fun changeDeviceScreen(action: () -> Unit) {
        if (fileOperationBusy) pendingFileClose = action else action()
    }
    LaunchedEffect(settings.serverUrl, clientState.userId) {
        sessionViewModel.bindIdentity(settings.serverUrl, clientState.userId)
        pendingFileClose = null
    }
    BackHandler(enabled = currentDestination == AppDestination.Devices && sessionViewModel.selectedDeviceId != null) {
        changeDeviceScreen {
            sessionViewModel.closeTools(clearDevice = sessionViewModel.deviceTab == DeviceTab.Overview)
        }
    }

    // The public build has no personal server: an empty or invalid saved address must never be
    // handed to the client (and must keep the onboarding visible even if the old flag says done).
    val serverUrlIsValid = remember(settings.serverUrl, settings.allowHttpInDebug) {
        settings.serverUrl.isNotBlank() &&
            ServerUrlValidator.validate(
                settings.serverUrl,
                BuildConfig.DEBUG && settings.allowHttpInDebug,
            ) == null
    }

    LaunchedEffect(settings.serverUrl, settings.allowHttpInDebug) {
        if (serverUrlIsValid) {
            meshClient.configureServer(settings.serverUrl)
        }
    }

    // A control client should not ask for the password again on every launch: try the stored
    // credentials once, and fall back to the sign-in screen only if that fails.
    LaunchedEffect(settings.serverUrl, settings.allowHttpInDebug, settings.onboardingCompleted) {
        if (settings.onboardingCompleted && serverUrlIsValid && !clientState.socketConnected &&
            secureVault.getCredentials(settings.serverUrl) != null
        ) {
            meshClient.loginWithStoredCredentials(settings.serverUrl)
        }
    }

    LaunchedEffect(settings.trustSelfSignedCertificates) {
        meshClient.setAllowSelfSignedCertificates(settings.trustSelfSignedCertificates)
    }

    DisposableEffect(meshClient) {
        // The client lives in a ViewModel: only stop the network work here, do not close it on
        // recomposition or rotation.
        onDispose { }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, meshClient) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Stop the remote screen stream while the app is in the background.
                Lifecycle.Event.ON_STOP -> meshClient.onAppBackground()
                Lifecycle.Event.ON_START -> meshClient.onAppForeground()
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

    if (!settings.onboardingCompleted || !serverUrlIsValid) {
        OnboardingScreen(
            settings = settings,
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
                onServerUrlSaved = { url -> settingsRepository.updateServerUrl(url); editingServer = false },
                onAllowHttpInDebugChanged = settingsRepository::updateAllowHttpInDebug,
                onTrustSelfSignedChanged = settingsRepository::updateTrustSelfSignedCertificates,
            )
        }
    }

    if (!loginDismissed && clientState.requiresSignIn && currentDestination != AppDestination.Settings) {
        LoginScreen(
            meshClient = meshClient,
            secureVault = secureVault,
            serverUrl = settings.serverUrl,
            onContinueWithoutSignIn = { loginDismissed = true },
            onEditServer = { editingServer = true },
        )
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
            dismissButton = { TextButton(onClick = { pendingFileClose = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (browserUnavailable) AlertDialog(
        onDismissRequest = { browserUnavailable = false },
        text = { Text(stringResource(R.string.device_browser_unavailable)) },
        confirmButton = { TextButton(onClick = { browserUnavailable = false }) { Text(stringResource(android.R.string.ok)) } },
    )

    Scaffold(
        contentWindowInsets = if (currentDestination == AppDestination.Session) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
        bottomBar = {
            // The live session owns the whole window: its own dock and back action replace the tab
            // bar, which otherwise eats a fifth of the canvas.
            if (currentDestination != AppDestination.Session && sessionViewModel.selectedDeviceId == null) {
                MeshNavigationBar(
                    current = currentDestination,
                    onSelect = { destination ->
                        if (currentDestination == AppDestination.Session && destination != AppDestination.Session) {
                            remoteFullscreen = false
                            onRemoteFullscreenChanged(false)
                        }
                        currentDestination = destination
                    },
                )
            }
        },
    ) { innerPadding ->
        Crossfade(
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
                    .consumeWindowInsets(innerPadding),
            ) {
                when (destination) {
                    AppDestination.Devices -> if (sessionViewModel.selectedDeviceId == null) DevicesScreen(
                        meshClient = meshClient,
                        favorites = favorites,
                        onAddFavorite = settingsRepository::addFavorite,
                        onRemoveFavorite = settingsRepository::removeFavorite,
                        onOpenRemote = { currentDestination = AppDestination.Session },
                        onOpenSettings = { currentDestination = AppDestination.Settings },
                        onSignIn = { loginDismissed = false },
                        onOpenDevice = sessionViewModel::openDevice,
                    ) else {
                        val device = clientState.devices.firstOrNull { it.nodeId == sessionViewModel.selectedDeviceId }
                        if (device == null) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.device_missing))
                                TextButton(onClick = { sessionViewModel.closeTools(clearDevice = true) }) {
                                    Text(stringResource(R.string.device_back))
                                }
                            }
                        } else DeviceScreen(
                            device = device,
                            server = clientState.serverCapabilities,
                            current = clientState.socketConnected && clientState.devicesCurrent,
                            selected = sessionViewModel.deviceTab,
                            onSelect = { tab -> if (tab != sessionViewModel.deviceTab) changeDeviceScreen {
                                if (tab == DeviceTab.Desktop) {
                                    sessionViewModel.closeTools()
                                    meshClient.connectDesktop(device)
                                    currentDestination = AppDestination.Session
                                } else sessionViewModel.selectDeviceTab(tab)
                            } },
                            onBack = { changeDeviceScreen { sessionViewModel.closeTools(clearDevice = true) } },
                            onWebPanel = {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settings.serverUrl))) }
                                catch (_: ActivityNotFoundException) { browserUnavailable = true }
                            },
                        ) {
                            when (sessionViewModel.deviceTab) {
                                DeviceTab.Terminal, DeviceTab.Ssh -> sessionViewModel.terminalController?.let { controller ->
                                    TerminalScreen(controller, onBack = { sessionViewModel.closeTools() })
                                }
                                DeviceTab.Files, DeviceTab.Sftp -> sessionViewModel.filesController?.let { controller ->
                                    FilesScreen(controller, onBack = { changeDeviceScreen { sessionViewModel.closeTools() } })
                                }
                                else -> Unit
                            }
                        }
                    }

                    AppDestination.Session -> RemoteDesktopScreen(
                        meshClient = meshClient,
                        onFullscreenChanged = {
                            remoteFullscreen = it
                            onRemoteFullscreenChanged(it)
                        },
                        onBrowseDevices = { sessionViewModel.closeTools(clearDevice = true); currentDestination = AppDestination.Devices },
                    )

                    AppDestination.Settings -> SettingsScreen(
                        settings = settings,
                        serverUrl = settings.serverUrl,
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
                        onCredentialsCleared = secureVault::clearCredentials,
                        onMeshSessionCleared = { sessionViewModel.closeTools(clearDevice = true); meshClient.logout(clearStoredCredentials = false) },
                        onWebViewSessionCleared = {
                            MeshWebViewController.clearWebViewSession(settingsRepository.appContext)
                        },
                    )
                }
            }
        }
    }
}
