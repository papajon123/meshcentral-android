package ru.papajon.meshcentralmobile.model

/**
 * Three destinations only: the computer list, the live session and settings. Signing in and picking a
 * server are flows inside these destinations instead of separate tabs, so the user never has to
 * hunt for the right tab to make progress.
 */
enum class AppDestination(val title: String, val tabLabel: String) {
    Devices("Computers", "PCs"),
    Session("Session", "Session"),
    Settings("Settings", "Settings"),
}
