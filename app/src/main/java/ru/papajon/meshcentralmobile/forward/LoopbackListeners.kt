package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface

/** Reserve every available loopback family. An occupied IPv6 port is never an IPv4-only fallback. */
internal object LoopbackListeners {
    fun ipv6Available(): Boolean = !java.lang.Boolean.getBoolean("java.net.preferIPv4Stack") &&
        NetworkInterface.getByInetAddress(InetAddress.getByName("::1"))?.isUp == true

    fun bind(requestedPort: Int, backlog: Int, ipv6Available: Boolean = ipv6Available()): List<ServerSocket> {
        var failure: Exception? = null
        repeat(if (requestedPort == 0) 16 else 1) {
            val ipv4 = ServerSocket()
            val ipv6 = if (ipv6Available) ServerSocket() else null
            try {
                // Linux/Android SO_REUSEADDR permits restart after TIME_WAIT, not a second listener.
                // Windows has different sharing semantics; retain exclusive binding in JVM tooling.
                val reuse = !System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
                ipv4.reuseAddress = reuse
                ipv6?.reuseAddress = reuse
                ipv4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), backlog)
                ipv6?.bind(InetSocketAddress(InetAddress.getByName("::1"), ipv4.localPort), backlog)
                return listOfNotNull(ipv4, ipv6)
            } catch (error: Exception) {
                failure = error
                runCatching { ipv4.close() }
                runCatching { ipv6?.close() }
            }
        }
        throw requireNotNull(failure)
    }
}

/** Signal cancellation/failure to EOF-delimited clients instead of a successful short response. */
internal fun resetLocalSocket(socket: Socket) {
    runCatching { socket.setSoLinger(true, 0) }
    runCatching { socket.close() }
}
