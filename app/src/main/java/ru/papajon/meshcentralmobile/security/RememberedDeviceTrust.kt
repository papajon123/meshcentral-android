package ru.papajon.meshcentralmobile.security

/** The immutable trust policy of the connection that issued a remembered-device cookie. */
sealed interface RememberedDeviceTrust {
    data object Platform : RememberedDeviceTrust
    data class Pinned(val spkiSha256: String) : RememberedDeviceTrust {
        init { require(spkiSha256.matches(Regex("[0-9a-f]{64}"))) }
    }

    /** Cleartext fixtures are isolated from both real TLS modes and exist only in debug builds. */
    data object DebugHttp : RememberedDeviceTrust
}
