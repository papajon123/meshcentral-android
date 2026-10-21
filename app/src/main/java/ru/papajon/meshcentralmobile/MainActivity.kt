package ru.papajon.meshcentralmobile

import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        settingsRepository = SettingsRepository(applicationContext)
        secureVault = SecureVault(applicationContext)
        appLockController = AppLockController(settingsRepository, secureVault)
        applyScreenshotBlocking(settingsRepository.getSettings().blockScreenshots)

        setContent {
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

    override fun onStart() {
        super.onStart()
        if (::appLockController.isInitialized) {
            appLockController.onAppForegrounded()
        }
    }

    override fun onStop() {
        if (::appLockController.isInitialized) {
            appLockController.onAppBackgrounded()
        }
        super.onStop()
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
