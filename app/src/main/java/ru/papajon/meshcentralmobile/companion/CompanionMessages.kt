package ru.papajon.meshcentralmobile.companion

import androidx.annotation.StringRes
import ru.papajon.meshcentralmobile.R

@StringRes
fun CompanionErrorCode.messageResourceId(): Int = when (this) {
    CompanionErrorCode.INVALID_REQUEST_ID -> R.string.companion_error_invalid_request_id
    CompanionErrorCode.UNKNOWN_OP -> R.string.companion_error_unknown_op
    CompanionErrorCode.UNAUTHENTICATED -> R.string.companion_error_unauthenticated
    CompanionErrorCode.FORBIDDEN -> R.string.companion_error_forbidden
    CompanionErrorCode.INVALID_ARGUMENT -> R.string.companion_error_invalid_argument
    CompanionErrorCode.UNSUPPORTED_OS -> R.string.companion_error_unsupported_os
    CompanionErrorCode.CONFIRMATION_REQUIRED -> R.string.companion_error_confirmation_required
    CompanionErrorCode.AGENT_OFFLINE -> R.string.companion_error_agent_offline
    CompanionErrorCode.OPERATION_CONFLICT -> R.string.companion_error_operation_conflict
    CompanionErrorCode.STALE_OPERATION -> R.string.companion_error_stale_operation
    CompanionErrorCode.QUOTA_EXCEEDED -> R.string.companion_error_quota_exceeded
    CompanionErrorCode.NODE_BUSY -> R.string.companion_error_node_busy
    CompanionErrorCode.JOB_NOT_FOUND -> R.string.companion_error_job_not_found
    CompanionErrorCode.CANCEL_UNSUPPORTED -> R.string.companion_error_cancel_unsupported
    CompanionErrorCode.INTERNAL_ERROR -> R.string.companion_error_internal
    CompanionErrorCode.NOT_AUTHENTICATED -> R.string.companion_error_not_authenticated
    CompanionErrorCode.DISCONNECTED -> R.string.companion_error_disconnected
    CompanionErrorCode.SEND_FAILED -> R.string.companion_error_send_failed
    CompanionErrorCode.TOO_MANY_REQUESTS -> R.string.companion_error_too_many_requests
    CompanionErrorCode.TIMEOUT -> R.string.companion_error_timeout
    CompanionErrorCode.NETWORK -> R.string.companion_error_network
    CompanionErrorCode.PROTOCOL -> R.string.companion_error_protocol
    CompanionErrorCode.UNSUPPORTED_PROTOCOL -> R.string.companion_error_unsupported_protocol
    CompanionErrorCode.JOB_ACTIVE -> R.string.companion_error_job_active
    CompanionErrorCode.PENDING_OPERATION -> R.string.companion_error_pending_operation
    CompanionErrorCode.UNKNOWN -> R.string.companion_error_unknown
}

@StringRes
fun CompanionJobErrorCode.messageResourceId(): Int = when (this) {
    CompanionJobErrorCode.MARKER_MISSING -> R.string.companion_job_error_marker_missing
    CompanionJobErrorCode.COMMAND_FAILED -> R.string.companion_job_error_command_failed
    CompanionJobErrorCode.INVALID_RESULT -> R.string.companion_job_error_invalid_result
    CompanionJobErrorCode.DISPATCH_FAILED -> R.string.companion_job_error_dispatch_failed
    CompanionJobErrorCode.TIMEOUT -> R.string.companion_job_error_timeout
    CompanionJobErrorCode.SERVER_RESTART -> R.string.companion_job_error_server_restart
    CompanionJobErrorCode.AGENT_WAIT_TIMEOUT -> R.string.companion_job_error_agent_wait_timeout
    CompanionJobErrorCode.UNSUPPORTED_OS -> R.string.companion_job_error_unsupported_os
    CompanionJobErrorCode.UNKNOWN -> R.string.companion_job_error_unknown
}
