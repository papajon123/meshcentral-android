package ru.papajon.meshcentralmobile.model

enum class ConnectionState(val label: String) {
    Offline("Offline"),
    Connecting("Connecting"),
    Authenticated("Authenticated"),
    LoadingRemoteDesktop("Loading remote desktop"),
    Connected("Connected"),
    Reconnecting("Reconnecting"),
    Failed("Failed"),
}
