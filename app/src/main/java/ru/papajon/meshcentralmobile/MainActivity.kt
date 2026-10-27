package ru.papajon.meshcentralmobile

import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.content.Intent
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import android.content.res.Configuration
import ru.papajon.meshcentralmobile.ui.screens.session.DesktopPipHost
import ru.papajon.meshcentralmobile.ui.screens.session.LocalDesktopPip
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.AppLockController
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.MeshCentralApp
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var secureVault: SecureVault
    private lateinit var appLockController: AppLockController
    private var handledChatDelivery = -1L
    private val desktopPip by lazy { DesktopPipHost(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        val workspace = ConnectionWorkspace.get(application)
        settingsRepository = SettingsRepository(applicationContext)
        secureVault = workspace.appLockVault
        appLockController = workspace.appLock
        if (savedInstanceState == null) intent?.let(::acceptLauncherIntent)
        else savedInstanceState.getString("pendingLauncherToken")?.let {
            ConnectionWorkspace.get(application).restoreLauncherDestination(it,
                savedInstanceState.getBoolean("launcherActivationAttempted", false))
        }
        applyScreenshotBlocking(settingsRepository.getSettings().blockScreenshots)
        handledChatDelivery = savedInstanceState?.getLong("handledChatDelivery", -1L) ?: -1L
        val delivery = intent?.getLongExtra(ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.EXTRA_DELIVERY, -1L) ?: -1L
        if (savedInstanceState == null || (delivery >= 0 && delivery != handledChatDelivery)) {
            intent?.let { ConnectionWorkspace.get(application).acceptShare(it) }
            handledChatDelivery = delivery
        }

        setContent {
            CompositionLocalProvider(LocalDesktopPip provides desktopPip) {
            MeshCentralMobileTheme {
                MeshCentralApp(
                    settingsRepository = settingsRepository,
                    secureVault = secureVault,
                    appLockController = appLockController,
                    onScreenshotBlockingChanged = ::applyScreenshotBlocking,
                    onBiometricUnlockRequested = ::requestBiometricUnlock,
                    onRemoteFullscreenChanged = ::applyRemoteFullscreen,
                )
            }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        desktopPip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        appLockController.onPipChanged(this, isInPictureInPictureMode)
        desktopPip.onModeChanged(isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onStart() {
        appLockController.onWindowStarted(this, isInPictureInPictureMode)
        super.onStart()
    }

    override fun onResume() {
        // This also checks an expired deadline when PiP becomes visible after screen off.
        appLockController.onWindowStarted(this, isInPictureInPictureMode)
        super.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLauncherIntent(intent)
        ConnectionWorkspace.get(application).acceptShare(intent)
        handledChatDelivery = intent.getLongExtra(ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.EXTRA_DELIVERY, -1L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Only a local delivery sequence is saved, never chat text or authentication material.
        outState.putLong("handledChatDelivery", handledChatDelivery)
        // Save only an unconsumed destination; never replay Activity.intent after recreation.
        outState.putString("pendingLauncherToken", ConnectionWorkspace.get(application).launcherDestination)
        outState.putBoolean("launcherActivationAttempted", ConnectionWorkspace.get(application).launcherActivationAttempted)
        super.onSaveInstanceState(outState)
    }

    private fun acceptLauncherIntent(intent: Intent) {
        if (!ru.papajon.meshcentralmobile.workspace.IncomingIntentPolicy.accepts(intent.flags)) return
        ru.papajon.meshcentralmobile.integration.DeviceShortcuts.token(intent)?.let {
            ConnectionWorkspace.get(application).launcherDestination = it
        }
    }

    override fun onStop() {
        appLockController.onWindowStopped(this, isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        appLockController.onWindowStopped(this, isChangingConfigurations)
        super.onDestroy()
    }

    private fun applyScreenshotBlocking(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyRemoteFullscreen(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestBiometricUnlock(onResult: (Boolean, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(false, getString(R.string.biometric_unlock_requires_android9))
            return
        }

        val cancellationSignal = CancellationSignal()
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.biometric_unlock_title))
            .setSubtitle(getString(R.string.biometric_unlock_subtitle))
            .setNegativeButton(
                getString(R.string.cancel),
                ContextCompat.getMainExecutor(this),
            ) { _, _ -> onResult(false, getString(R.string.biometric_unlock_cancelled)) }
            .build()

        prompt.authenticate(
            cancellationSignal,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true, null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onResult(false, errString?.toString() ?: getString(R.string.biometric_unlock_failed))
                }

                override fun onAuthenticationFailed() {
                    onResult(false, getString(R.string.biometric_match_rejected))
                }
            },
        )
    }
}
