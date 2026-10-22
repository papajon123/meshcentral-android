package ru.papajon.meshcentralmobile.companion

/** Wire identity of the optional MeshCentral companion plugin. */
const val COMPANION_PLUGIN_ID = "mobilecompanion"
const val COMPANION_PROTOCOL_VERSION = 1

/** Allowlisted server templates. Nothing outside this list is ever started. */
enum class CompanionTemplate(val id: String, val readOnly: Boolean) {
    DIAGNOSTICS("diagnostics", true),
    SERVICES("services", true),
    PROCESSES("processes", true),
    SERVICE_START("service-start", false),
    SERVICE_STOP("service-stop", false),
    SERVICE_RESTART("service-restart", false),
    ;

    val mutation: Boolean get() = !readOnly

    companion object {
        fun fromId(id: String?): CompanionTemplate? =
            entries.firstOrNull { it.id == id }

        val readOnlyTemplates: List<CompanionTemplate> = entries.filter { it.readOnly }
    }
}

enum class CompanionServiceAction(val template: CompanionTemplate) {
    START(CompanionTemplate.SERVICE_START),
    STOP(CompanionTemplate.SERVICE_STOP),
    RESTART(CompanionTemplate.SERVICE_RESTART),
    ;

    companion object {
        fun fromTemplate(template: CompanionTemplate): CompanionServiceAction? =
            entries.firstOrNull { it.template == template }
    }
}

enum class CompanionJobStatus(val wire: String) {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    ;

    val active: Boolean get() = this == QUEUED || this == RUNNING
    val terminal: Boolean get() = !active

    companion object {
        fun fromWire(value: String?): CompanionJobStatus =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Server job diagnostic codes. They are shown as localized text, never as a server message. */
enum class CompanionJobErrorCode(val wire: String) {
    MARKER_MISSING("MARKER_MISSING"),
    COMMAND_FAILED("COMMAND_FAILED"),
    INVALID_RESULT("INVALID_RESULT"),
    DISPATCH_FAILED("DISPATCH_FAILED"),
    TIMEOUT("TIMEOUT"),
    SERVER_RESTART("SERVER_RESTART"),
    AGENT_WAIT_TIMEOUT("AGENT_WAIT_TIMEOUT"),
    UNSUPPORTED_OS("UNSUPPORTED_OS"),
    UNKNOWN("UNKNOWN"),
    ;

    companion object {
        fun fromWire(value: String?): CompanionJobErrorCode? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.wire == value } ?: UNKNOWN
        }
    }
}

enum class CompanionEventKind(val wire: String) {
    JOB_COMPLETED("job-completed"),
    JOB_FAILED("job-failed"),
    DEVICE_ONLINE("device-online"),
    DEVICE_OFFLINE("device-offline"),
    DEVICE_REQUEST_HELP("device-requesthelp"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): CompanionEventKind =
            entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * Every failure is a known code. The raw server `message` is deliberately discarded so it can
 * never leak into the UI, logs, saved state, or a shared report.
 */
enum class CompanionErrorCode(val wire: String?) {
    INVALID_REQUEST_ID("INVALID_REQUEST_ID"),
    UNKNOWN_OP("UNKNOWN_OP"),
    UNAUTHENTICATED("UNAUTHENTICATED"),
    FORBIDDEN("FORBIDDEN"),
    INVALID_ARGUMENT("INVALID_ARGUMENT"),
    UNSUPPORTED_OS("UNSUPPORTED_OS"),
    CONFIRMATION_REQUIRED("CONFIRMATION_REQUIRED"),
    AGENT_OFFLINE("AGENT_OFFLINE"),
    OPERATION_CONFLICT("OPERATION_CONFLICT"),
    STALE_OPERATION("STALE_OPERATION"),
    QUOTA_EXCEEDED("QUOTA_EXCEEDED"),
    NODE_BUSY("NODE_BUSY"),
    JOB_NOT_FOUND("JOB_NOT_FOUND"),
    CANCEL_UNSUPPORTED("CANCEL_UNSUPPORTED"),
    INTERNAL_ERROR("INTERNAL_ERROR"),
    NOT_AUTHENTICATED("not_authenticated"),
    DISCONNECTED("disconnected"),
    SEND_FAILED("send_failed"),
    TOO_MANY_REQUESTS("too_many_requests"),
    TIMEOUT(null),
    NETWORK(null),
    PROTOCOL(null),
    UNSUPPORTED_PROTOCOL(null),
    JOB_ACTIVE(null),
    PENDING_OPERATION(null),
    UNKNOWN(null),
    ;

    companion object {
        fun fromWire(value: String?): CompanionErrorCode =
            entries.firstOrNull { it.wire != null && it.wire == value } ?: UNKNOWN
    }
}

class CompanionException(
    val code: CompanionErrorCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

data class PendingOperation(
    val operationId: String,
    val createdAt: Long,
    val template: CompanionTemplate,
    val serviceName: String? = null,
    val confirmed: Boolean,
) {
    val parameters: Map<String, String>
        get() = serviceName?.takeIf { it.isNotBlank() }?.let { mapOf("name" to it) } ?: emptyMap()
}
