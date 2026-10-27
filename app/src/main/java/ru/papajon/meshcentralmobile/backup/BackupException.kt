package ru.papajon.meshcentralmobile.backup

/** Machine-readable reason for a rejected backup operation. Never carries payload data. */
enum class BackupFailureReason {
    NewerVersion,
    PasswordOrTampered,
    Corrupt,
    Limit,
    UnsupportedFormat,
}

/**
 * A rejected backup operation. The message is deliberately generic: callers map [reason] to a
 * localized user-facing string and must not log decoded values.
 */
class BackupException(val reason: BackupFailureReason) : IllegalArgumentException("Backup is invalid")
