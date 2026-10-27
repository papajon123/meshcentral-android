package ru.papajon.meshcentralmobile.workspace

import android.content.Intent

/** History launches restore the task, not the original external delivery. */
internal object IncomingIntentPolicy {
    fun accepts(flags: Int): Boolean = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
}
