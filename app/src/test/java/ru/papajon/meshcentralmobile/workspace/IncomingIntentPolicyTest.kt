package ru.papajon.meshcentralmobile.workspace

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class IncomingIntentPolicyTest {
    @Test fun freshLauncherAndShareDeliveriesAreAccepted() {
        assertTrue(IncomingIntentPolicy.accepts(0))
        assertTrue(IncomingIntentPolicy.accepts(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertTrue(IncomingIntentPolicy.accepts(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    @Test fun historyFlagRejectsBothStandaloneAndCombinedDeliveries() {
        assertFalse(IncomingIntentPolicy.accepts(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertFalse(IncomingIntentPolicy.accepts(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
