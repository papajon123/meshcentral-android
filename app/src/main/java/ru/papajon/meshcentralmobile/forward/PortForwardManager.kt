package ru.papajon.meshcentralmobile.forward

import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PortConnection : AutoCloseable {
    fun start()
    /** Cancellation closes the local stream with RST; a natural remote EOF is handled separately. */
    override fun close()
    fun abort(error: ForwardError) { close() }
}

/** Application owned, bounded, never restarts a route or persists live state. */
class PortForwardManager(
    private val allowed: (PortForwardRule) -> Boolean,
    private val connect: (PortForwardRule, Socket, (Long, Long) -> Unit, (ForwardError?) -> Unit) -> PortConnection,
    private val maxRules: Int = 8,
    private val maxConnections: Int = 16,
    private val idleTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(IDLE_TIMEOUT_MINUTES.toLong()),
) : AutoCloseable {
    private class Route(val rule: PortForwardRule, val listeners: List<LoopbackListener>) {
        val connections = mutableSetOf<PortConnection>()
        var idleTask: ScheduledFuture<*>? = null
        var idleVersion = 0L
    }
    private val lock = Any()
    private val routes = mutableMapOf<String, Route>()
    private val mutableStates = MutableStateFlow<List<PortForwardState>>(emptyList())
    val states = mutableStates.asStateFlow()
    private var closed = false
    private val acceptors = Executors.newFixedThreadPool(maxRules * 2) { r -> Thread(r, "tcp-listener").apply { isDaemon = true } }
    private val idleTimer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "tcp-idle").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    init { require(idleTimeoutMillis > 0) }

    fun start(rule: PortForwardRule): Boolean = synchronized(lock) {
        if (closed) return false
        if (routes.containsKey(rule.id)) return true
        val error = when {
            !rule.valid() -> ForwardError.INVALID_RULE
            !allowed(rule) -> ForwardError.UNAVAILABLE
            routes.size >= maxRules -> ForwardError.LIMIT
            else -> null
        }
        if (error != null) { publish(PortForwardState(rule, error = error)); return false }
        val listeners = try {
            LoopbackListeners.bind(rule.localPort, maxConnections).map(::LoopbackListener)
        } catch (_: Exception) {
            publish(PortForwardState(rule, error = ForwardError.PORT_IN_USE))
            return false
        }
        val route = Route(rule, listeners)
        routes[rule.id] = route
        publish(PortForwardState(rule, listeners.first().socket.localPort, running = true))
        listeners.forEach { listener -> acceptors.execute { accept(route, listener) } }
        scheduleIdle(route)
        true
    }

    private fun accept(route: Route, listener: LoopbackListener) {
        try {
            while (!listener.isClosed) {
                val local = listener.accept()
                synchronized(lock) {
                    if (routes[route.rule.id] !== route || !allowed(route.rule)) {
                        resetLocalSocket(local); stopRoute(route, ForwardError.UNAVAILABLE); return
                    }
                    if (routes.values.sumOf { it.connections.size } >= maxConnections) {
                        resetLocalSocket(local); update(route) { it.copy(error = ForwardError.LIMIT) }
                    } else {
                        cancelIdle(route)
                        local.tcpNoDelay = true
                        var connection: PortConnection? = null
                        try {
                            connection = connect(route.rule, local,
                                { sent, received -> synchronized(lock) {
                                    update(route) { it.copy(bytesSent = it.bytesSent + sent, bytesReceived = it.bytesReceived + received) }
                                } },
                                { error -> synchronized(lock) {
                                    if (!route.connections.remove(connection)) return@synchronized
                                    update(route) { it.copy(connections = route.connections.size, error = error ?: it.error) }
                                    val pairingFailure = error == ForwardError.TIMEOUT || error == ForwardError.CONNECT_FAILED
                                    if (error?.stopsRule == true && (!pairingFailure || route.connections.isEmpty())) stopRoute(route, error)
                                    else if (route.connections.isEmpty()) scheduleIdle(route)
                                } })
                            route.connections.add(connection)
                            update(route) { it.copy(connections = route.connections.size, error = null) }
                            connection.start()
                        } catch (_: Exception) {
                            connection?.abort(ForwardError.CONNECT_FAILED)
                            route.connections.remove(connection)
                            resetLocalSocket(local)
                            if (route.connections.isEmpty()) stopRoute(route, ForwardError.CONNECT_FAILED)
                            else update(route) { it.copy(connections = route.connections.size, error = ForwardError.CONNECT_FAILED) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { if (routes[route.rule.id] === route) stopRoute(route, ForwardError.CONNECTION_LOST) }
        }
    }

    fun stop(id: String) = synchronized(lock) { routes[id]?.let { stopRoute(it, null) }; Unit }
    /** Immutable diagnostic snapshot of the actual kernel bindings, not configured defaults. */
    internal fun boundEndpoints(id: String): List<InetSocketAddress> = synchronized(lock) {
        routes[id]?.listeners?.map { it.socket.localSocketAddress as InetSocketAddress }.orEmpty()
    }
    fun stopAll() = synchronized(lock) { routes.values.toList().forEach { stopRoute(it, null) } }
    fun stopDevice(profileId: String, nodeId: String) = synchronized(lock) {
        routes.values.filter { it.rule.profileId == profileId && it.rule.nodeId == nodeId }.toList()
            .forEach { stopRoute(it, null) }
    }
    fun validateSession() = synchronized(lock) {
        routes.values.toList().filter { !allowed(it.rule) }.forEach { stopRoute(it, ForwardError.UNAVAILABLE) }
    }
    fun serviceFailed() = synchronized(lock) {
        routes.values.toList().forEach { stopRoute(it, ForwardError.SERVICE) }
    }
    private fun cancelIdle(route: Route) {
        route.idleVersion++
        route.idleTask?.cancel(false)
        route.idleTask = null
    }
    private fun scheduleIdle(route: Route) {
        if (routes[route.rule.id] !== route || route.connections.isNotEmpty()) return
        cancelIdle(route)
        val version = route.idleVersion
        route.idleTask = idleTimer.schedule({ synchronized(lock) {
            if (routes[route.rule.id] === route && route.idleVersion == version && route.connections.isEmpty()) {
                stopRoute(route, null, idle = true)
            }
        } }, idleTimeoutMillis, TimeUnit.MILLISECONDS)
    }
    private fun stopRoute(route: Route, error: ForwardError?, idle: Boolean = false) {
        if (routes[route.rule.id] !== route) return
        routes.remove(route.rule.id) // Invalidate before close callbacks.
        cancelIdle(route)
        route.listeners.forEach { runCatching { it.close() } }
        val connections = route.connections.toList()
        route.connections.clear()
        connections.forEach { runCatching { if (error == null) it.close() else it.abort(error) } }
        mutableStates.value = mutableStates.value.map {
            if (it.rule.id == route.rule.id) it.copy(running = false, connections = 0, error = error, autoStopped = idle) else it
        }
    }
    private fun update(route: Route, change: (PortForwardState) -> PortForwardState) {
        if (routes[route.rule.id] !== route) return
        mutableStates.value = mutableStates.value.map { if (it.rule.id == route.rule.id) change(it) else it }
    }
    private fun publish(state: PortForwardState) {
        // History is UI-only and bounded even if rules are repeatedly deleted and recreated.
        val old = mutableStates.value.filter { it.rule.id != state.rule.id }
        val keep = if (old.size >= 64) old.filter { it.running } + old.filter { !it.running }.takeLast(63 - old.count { it.running }) else old
        mutableStates.value = keep + state
    }
    override fun close() = synchronized(lock) { closed = true; stopAll(); acceptors.shutdownNow(); idleTimer.shutdownNow(); Unit }
    companion object { const val IDLE_TIMEOUT_MINUTES = 5 }
}
