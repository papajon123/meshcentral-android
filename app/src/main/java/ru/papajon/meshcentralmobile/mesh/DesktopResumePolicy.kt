package ru.papajon.meshcentralmobile.mesh

enum class DesktopResumeAction { None, WaitForControl, WaitForRelay, Refresh, Reconnect }

/** Decides how to restore the active desktop when Android brings the app to the foreground. */
object DesktopResumePolicy {
    fun onForeground(
        hasSession: Boolean,
        controlReady: Boolean,
        relaySocketOpen: Boolean,
        handshakeComplete: Boolean,
    ): DesktopResumeAction = when {
        !hasSession -> DesktopResumeAction.None
        !controlReady -> DesktopResumeAction.WaitForControl
        !relaySocketOpen -> DesktopResumeAction.Reconnect
        !handshakeComplete -> DesktopResumeAction.WaitForRelay
        else -> DesktopResumeAction.Refresh
    }
}

/** Each relay has its own KVM handshake even when its screen size matches the previous relay. */
class DesktopInitialisationTracker {
    private var initialised = false

    fun onNewRelay() { initialised = false }

    fun shouldInitialise(widthChanged: Boolean): Boolean = widthChanged || !initialised

    fun markInitialised() { initialised = true }
}
