package ru.papajon.meshcentralmobile.model

data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val onboardingCompleted: Boolean = false,
    val allowHttpInDebug: Boolean = false,
    /** Debug builds only: accept self-signed certificates for a local MeshCentral test server. */
    val trustSelfSignedCertificates: Boolean = false,
    val blockScreenshots: Boolean = false,
    val biometricUnlockEnabled: Boolean = false,
    val lockTimeoutSeconds: Long = DEFAULT_LOCK_TIMEOUT_SECONDS,
    val stylusPrecisionEnabled: Boolean = true,
    val stylusHintSeen: Boolean = false,
)

/** No personal server is baked in: the first launch asks the user for their own HTTPS address. */
const val DEFAULT_SERVER_URL = ""
const val DEFAULT_LOCK_TIMEOUT_SECONDS = 60L
