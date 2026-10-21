package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopResumePolicyTest {
    @Test
    fun foregroundRefreshesAnOpenDesktopAndReconnectsADeadRelay() {
        assertEquals(
            DesktopResumeAction.Refresh,
            DesktopResumePolicy.onForeground(hasSession = true, controlReady = true,
                relaySocketOpen = true, handshakeComplete = true),
        )
        assertEquals(
            DesktopResumeAction.Reconnect,
            DesktopResumePolicy.onForeground(hasSession = true, controlReady = true,
                relaySocketOpen = false, handshakeComplete = false),
        )
        assertEquals(
            DesktopResumeAction.WaitForControl,
            DesktopResumePolicy.onForeground(hasSession = true, controlReady = false,
                relaySocketOpen = false, handshakeComplete = false),
        )
        assertEquals(
            DesktopResumeAction.None,
            DesktopResumePolicy.onForeground(hasSession = false, controlReady = true,
                relaySocketOpen = false, handshakeComplete = false),
        )
    }

    @Test
    fun newRelayRequiresFreshKvmInitialisation() {
        val tracker = DesktopInitialisationTracker()
        assertEquals(true, tracker.shouldInitialise(widthChanged = false))
        tracker.markInitialised()
        assertEquals(false, tracker.shouldInitialise(widthChanged = false))
        tracker.onNewRelay()
        assertEquals(true, tracker.shouldInitialise(widthChanged = false))
    }
}
