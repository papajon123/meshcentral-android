package ru.papajon.meshcentralmobile.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * Owns the MeshCentral session for the whole activity lifetime.
 *
 * Keeping the client in a ViewModel is what makes the remote desktop survive a rotation or any other
 * configuration change: a `remember { }` value would be thrown away and the socket with it.
 */
class MeshSessionViewModel(application: Application) : AndroidViewModel(application) {
    val client: MeshCentralClient by lazy {
        MeshCentralClient(secureVault = SecureVault(application), scope = viewModelScope)
    }

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { client.onNetworkAvailable() }
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        client.close()
        super.onCleared()
    }
}
