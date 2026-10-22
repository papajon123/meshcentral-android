package ru.papajon.meshcentralmobile.notifications

/** Event kinds produced by the read-only `mobilecompanion/events` endpoint. */
enum class NotificationEventKind(val wire: String) {
    DEVICE_ONLINE("device-online"),
    DEVICE_OFFLINE("device-offline"),
    DEVICE_REQUEST_HELP("device-requesthelp"),
    JOB_COMPLETED("job-completed"),
    JOB_FAILED("job-failed"),
    DEVICE_ALERT("device-alert"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String): NotificationEventKind? = byWire[value]
    }
}

/** Stable, user-visible failure states. Nothing here contains a token or a response body. */
enum class NotificationError {
    NOT_ENABLED,
    NOT_SIGNED_IN,
    ACCOUNT_MISMATCH,
    PROFILE_MISSING,
    SELF_SIGNED_UNSUPPORTED,
    INSECURE_TRANSPORT,
    TOKEN_EXPIRED,
    NETWORK,
    TLS_UNTRUSTED,
    HTTP,
    RATE_LIMITED,
    PROTOCOL,
    INVALID_ENDPOINT,
    UNKNOWN,
}

/**
 * One server event after the allowlisted parse. `nodeId` and `jobId` are opaque identifiers for a
 * future in-app open action only; they are never rendered in the notification text.
 */
data class NotificationFeedItem(
    val id: String,
    val kind: NotificationEventKind,
    val nodeId: String?,
    val jobId: String?,
    val time: Long,
    val expiresAt: Long,
)

data class QuietHours(
    val enabled: Boolean = false,
    val startHour: Int = DEFAULT_START_HOUR,
    val endHour: Int = DEFAULT_END_HOUR,
) {
    companion object {
        const val DEFAULT_START_HOUR = 22
        const val DEFAULT_END_HOUR = 7
    }
}

data class NotificationSettings(
    val kinds: Set<NotificationEventKind> = NotificationEventKind.entries.toSet(),
    val quietHours: QuietHours = QuietHours(),
)

/** Everything the settings sheet needs; deliberately excludes the bearer token. */
data class NotificationUiState(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = true,
    val backgroundSupported: Boolean = true,
    val unsupportedReason: NotificationError? = null,
    val profileAvailable: Boolean = true,
    val tokenExpiresAt: Long? = null,
    val lastCheckAt: Long? = null,
    val lastError: NotificationError? = null,
    val busy: Boolean = false,
    val settings: NotificationSettings = NotificationSettings(),
    val feed: List<NotificationFeedItem> = emptyList(),
)

/** Public contract shared with MainActivity/Settings integration. */
object NotificationContract {
    const val LABEL = "notifications"
    const val PLUGIN_ID = "mobilecompanion"
    const val ENDPOINT_SUFFIX = "/mobilecompanion/events"
    const val POLL_INTERVAL_MINUTES = 15L
    const val TOKEN_TTL_DAYS = 30
    const val MAX_TOKEN_TTL_MILLIS = 31L * 24L * 60L * 60L * 1000L
    const val DISK_ALERT_PERCENT = 90
    const val MEMORY_ALERT_PERCENT = 95

    const val EXTRA_PROFILE_ID = "profileId"
    const val EXTRA_NODE_ID = "nodeId"
    const val EXTRA_JOB_ID = "jobId"

    const val NOTIFICATION_CHANNEL_ID = "mobilecompanion_events"
}

/** The token is returned by the websocket only once and must live in SecureVault. */
data class NotificationToken(
    val tokenId: String,
    val token: String,
    val endpoint: String,
    val expiresAt: Long,
    val userId: String,
) {
    /** Keeps an accidental data-class log from printing the bearer secret. */
    override fun toString(): String =
        "NotificationToken(tokenId=$tokenId, endpoint=$endpoint, expiresAt=$expiresAt, userId=$userId)"
}

data class NotificationEventPage(
    val items: List<NotificationFeedItem>,
    val cursor: Long,
    val resetCursor: Boolean,
)

enum class NotificationPollResult {
    SUCCESS,
    RETRY,
    TOKEN_EXPIRED,
    DISABLED,
    UNAVAILABLE,
    PROFILE_MISSING,
}
