package ru.papajon.meshcentralmobile.transfer

import ru.papajon.meshcentralmobile.localization.UiText

/**
 * Result of one queued upload. Success is emitted only after the protocol's own completion and the
 * remote read-back (size + SHA-256) have both been audited. A message string is never used as proof.
 */
sealed interface TransferOutcome {
    data class Success(val bytes: Long) : TransferOutcome

    data class Failed(val error: UiText) : TransferOutcome

    /** [partialRemote] is true when the remote destination may have been written or replaced. */
    data class Cancelled(val partialRemote: Boolean) : TransferOutcome
}
