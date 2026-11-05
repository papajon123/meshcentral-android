package ru.papajon.meshcentralmobile.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.papajon.meshcentralmobile.R

/** Android owns the credential UI and validation; no credential or success is persisted here. */
class DeviceAuthentication(private val activity: FragmentActivity) {
    private class Request(val result: (Boolean, String?) -> Unit) {
        var prompt: BiometricPrompt? = null
    }
    private var request: Request? = null
    private var completion: Job? = null
    private var generation = 0L

    fun authenticate(hasAppPin: Boolean, result: (Boolean, String?) -> Unit) {
        if (request != null) return
        val info = promptInfo(activity, hasAppPin)
        if (BiometricManager.from(activity).canAuthenticate(info.allowedAuthenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            result(false, activity.getString(R.string.auth_unavailable))
            return
        }
        val active = Request(result)
        request = active
        val currentGeneration = ++generation
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authentication: BiometricPrompt.AuthenticationResult) {
                    if (request !== active) return
                    request = null
                    // Credential confirmation is a system Activity on older Android versions.
                    // Its result can arrive before our Activity resumes; never unlock background UI.
                    completion = activity.lifecycleScope.launch {
                        val resumed = awaitAuthenticationForeground(activity.lifecycle)
                        if (generation == currentGeneration) {
                            active.result(resumed && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED), null)
                        }
                    }
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (request !== active) return
                    request = null
                    val cancelled = code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    active.result(false, if (cancelled) null else message.toString())
                }
                // A rejected scan leaves the system prompt open; it is not a completed request.
            })
        active.prompt = prompt
        try {
            prompt.authenticate(info)
        } catch (_: RuntimeException) {
            if (request === active) {
                request = null
                active.result(false, activity.getString(R.string.auth_unavailable))
            }
        }
    }

    /** The Activity owns this instance; a destroyed owner cannot accept a late authentication. */
    fun cancel() {
        generation++
        completion?.cancel()
        completion = null
        val active = request
        request = null
        active?.prompt?.cancelAuthentication()
        active?.result?.invoke(false, null)
    }

    companion object {
        internal fun promptInfo(context: Context, hasAppPin: Boolean): BiometricPrompt.PromptInfo {
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.biometric_unlock_title))
                .setSubtitle(context.getString(if (hasAppPin) R.string.biometric_unlock_subtitle else R.string.auth_device_subtitle))
                // WEAK | DEVICE_CREDENTIAL is supported by AndroidX on API 26–29 too.
                .setAllowedAuthenticators(if (hasAppPin) BIOMETRIC_WEAK else BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            if (hasAppPin) builder.setNegativeButtonText(context.getString(R.string.lock_action_use_pin))
            return builder.build()
        }
    }
}

/** A platform result may precede onResume, but cannot authorize an arbitrarily late return. */
internal suspend fun awaitAuthenticationForeground(lifecycle: androidx.lifecycle.Lifecycle, timeoutMillis: Long = 5_000): Boolean =
    try {
        withTimeoutOrNull(timeoutMillis) { lifecycle.withResumed { true } } == true
    } catch (_: androidx.lifecycle.LifecycleDestroyedException) {
        false
    }
