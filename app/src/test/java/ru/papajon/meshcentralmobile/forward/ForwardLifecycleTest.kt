package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ForwardLifecycleTest {
    private fun rule() = PortForwardRule(profileId = "one", nodeId = "node//one", name = "One", remotePort = 22)

    @Test fun actualBindingsAreExactlyAvailableLoopbacksOnSamePort() {
        val listeners = LoopbackListeners.bind(0, 4)
        try {
            assertEquals(availableAddresses(), listeners.map { it.inetAddress }.toSet())
            assertTrue(listeners.all { it.inetAddress.isLoopbackAddress && !it.inetAddress.isAnyLocalAddress })
            assertEquals(1, listeners.map { it.localPort }.toSet().size)
            listeners.forEach { listener ->
                ServerSocket().use { competing ->
                    try { competing.bind(listener.localSocketAddress); fail("Loopback port must be exclusively occupied") }
                    catch (_: java.net.BindException) { }
                }
            }
        } finally { listeners.forEach { it.close() } }
    }

    @Test fun ipv6CollisionRollsBackIpv4Binding() {
        org.junit.Assume.assumeTrue(InetAddress.getByName("::1") in availableAddresses())
        ServerSocket(0, 4, InetAddress.getByName("::1")).use { ipv6 ->
            PortForwardManager({ true }, { _, _, _, _ -> error("unused") }).use { manager ->
                assertFalse(manager.start(rule().copy(localPort = ipv6.localPort)))
                assertEquals(ForwardError.PORT_IN_USE, manager.states.value.single().error)
                ServerSocket(ipv6.localPort, 4, InetAddress.getByName("127.0.0.1")).use { assertTrue(it.isBound) }
            }
        }
    }

    @Test fun bothFamiliesReachSameRuleAndBothPortsAreReleased() {
        val accepted = CopyOnWriteArrayList<Socket>()
        var port = 0
        PortForwardManager({ true }, { _, socket, _, finished ->
            accepted.add(socket)
            object : PortConnection {
                override fun start() = Unit
                override fun close() { socket.close(); finished(null) }
            }
        }).use { manager ->
            val rule = rule()
            manager.start(rule); port = manager.states.value.single().localPort
            val endpoints = manager.boundEndpoints(rule.id)
            assertEquals(availableAddresses(), endpoints.map { it.address }.toSet())
            assertTrue(endpoints.all { !it.address.isAnyLocalAddress && it.port == port })
            val clients = endpoints.map { Socket(it.address, port) }
            try {
                await { accepted.size == endpoints.size }
                assertEquals(availableAddresses(), accepted.map { it.localAddress }.toSet())
                assertEquals(endpoints.size, manager.states.value.single().connections)
            } finally { clients.forEach { it.close() } }
        }
        LoopbackListeners.bind(port, 4).forEach { it.close() }
    }

    @Test fun unusedRuleAutomaticallyStopsAndReleasesBothListeners() {
        PortForwardManager({ true }, { _, _, _, _ -> error("unused") }, idleTimeoutMillis = 100).use { manager ->
            manager.start(rule()); val port = manager.states.value.single().localPort
            await { !manager.states.value.single().running }
            assertTrue(manager.states.value.single().autoStopped)
            assertNull(manager.states.value.single().error)
            LoopbackListeners.bind(port, 4).forEach { it.close() }
        }
    }

    @Test fun idleConnectionIsNotAnIdleRuleAndLastDisconnectStartsFreshDeadline() {
        var end: (() -> Unit)? = null
        PortForwardManager({ true }, { _, socket, _, finished ->
            end = { socket.close(); finished(null) }
            object : PortConnection { override fun start() = Unit; override fun close() { end?.invoke() } }
        }, idleTimeoutMillis = 500).use { manager ->
            manager.start(rule())
            Socket("127.0.0.1", manager.states.value.single().localPort).use {
                await { manager.states.value.single().connections == 1 }
                Thread.sleep(1000)
                assertTrue(manager.states.value.single().running)
                end!!.invoke()
                Thread.sleep(150)
                assertTrue(manager.states.value.single().running)
                await { !manager.states.value.single().running }
                assertTrue(manager.states.value.single().autoStopped)
            }
        }
    }

    @Test fun deviceStopDoesNotStopAnotherDeviceOrProfile() {
        PortForwardManager({ true }, { _, _, _, _ -> error("unused") }).use { manager ->
            val first = rule()
            val anotherDevice = rule().copy(nodeId = "node//two")
            val anotherProfile = rule().copy(profileId = "two")
            listOf(first, anotherDevice, anotherProfile).forEach { assertTrue(manager.start(it)) }
            manager.stopDevice(first.profileId, first.nodeId)
            assertFalse(manager.states.value.single { it.rule.id == first.id }.running)
            assertTrue(manager.states.value.filter { it.rule.id != first.id }.all { it.running })
        }
    }

    @Test fun notificationFlowIgnoresTrafficButPublishesConnectionChanges() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = MutableStateFlow<List<PortForwardState>>(emptyList())
        val observed = CopyOnWriteArrayList<List<ForwardNotificationEntry>>()
        try {
            scope.launch { source.forwardNotifications().collect { observed.add(it) } }
            val state = PortForwardState(rule(), localPort = 40000, running = true)
            source.value = listOf(state)
            repeat(100) { source.value = listOf(state.copy(bytesSent = it.toLong(), bytesReceived = it * 30L)) }
            source.value = listOf(state.copy(error = ForwardError.CONNECTION_LOST))
            assertEquals(listOf(emptyList(), listOf(ForwardNotificationEntry("One", 40000))), observed.toList())
            source.value = listOf(state.copy(connections = 1))
            repeat(100) { source.value = listOf(state.copy(connections = 1, bytesReceived = it.toLong())) }
            assertEquals(3, observed.size)
            assertEquals(1, observed.last().single().connections)
            source.value = listOf(state.copy(localPort = 40001))
            source.value = listOf(state.copy(running = false))
            assertEquals(5, observed.size)
        } finally { scope.cancel() }
    }

    @Test fun notificationBurstKeepsTheLastCountWithAtMostTwoPublications() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = MutableStateFlow(listOf(PortForwardState(rule(), localPort = 40000, running = true)))
        val observed = CopyOnWriteArrayList<List<ForwardNotificationEntry>>()
        val times = CopyOnWriteArrayList<Long>()
        try {
            scope.launch { source.forwardNotifications().coalescedForwardNotifications().collect {
                observed.add(it); times.add(System.nanoTime())
            } }
            await { observed.isNotEmpty() }
            repeat(100) { index -> source.value = listOf(source.value.single().copy(connections = index % 16 + 1)) }
            await { observed.last().single().connections == 4 }
            assertEquals(2, observed.size)
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(times[1] - times[0]) >= 1000)
        } finally { scope.cancel() }
    }

    @Test fun connectFactoryExceptionKeepsSiblingsButStopsAnOtherwiseEmptyRule() {
        for (withSibling in listOf(false, true)) {
            val opened = CopyOnWriteArrayList<Socket>()
            val throwing = java.util.concurrent.atomic.AtomicBoolean()
            PortForwardManager({ true }, { _, socket, _, finish ->
                if (throwing.get()) throw java.io.IOException("Fixture connect failure")
                opened.add(socket)
                object : PortConnection {
                    override fun start() = Unit
                    override fun close() { resetLocalSocket(socket); finish(null) }
                }
            }).use { manager ->
                val rule = rule(); assertTrue(manager.start(rule))
                val port = manager.states.value.single().localPort
                val sibling = if (withSibling) Socket("127.0.0.1", port) else null
                try {
                    if (withSibling) await { manager.states.value.single().connections == 1 }
                    throwing.set(true)
                    try {
                        Socket("127.0.0.1", port).use { rejected ->
                            rejected.soTimeout = 3000
                            try { rejected.getInputStream().read(); fail("Expected a reset") } catch (_: java.net.SocketException) { }
                        }
                    } catch (_: java.net.SocketException) {
                        // A slow Linux runner may see the reset already while connecting; the state checks below
                        // still prove that the factory failure was handled.
                    }
                    await { manager.states.value.single().error == ForwardError.CONNECT_FAILED }
                    assertEquals(withSibling, manager.states.value.single().running)
                    assertEquals(if (withSibling) 1 else 0, manager.states.value.single().connections)
                    if (sibling != null) {
                        sibling.soTimeout = 3000
                        opened.single().getOutputStream().write(51)
                        assertEquals(51, sibling.getInputStream().read())
                    }
                } finally { sibling?.close() }
            }
        }
    }

    @Test fun failedNewPairingDoesNotCloseExistingConnections() {
        for (error in listOf(ForwardError.TIMEOUT, ForwardError.CONNECT_FAILED)) {
            val endings = CopyOnWriteArrayList<(ForwardError?) -> Unit>()
            PortForwardManager({ true }, { _, socket, _, finish ->
                endings.add { error -> resetLocalSocket(socket); finish(error) }
                object : PortConnection {
                    override fun start() = Unit
                    override fun close() { resetLocalSocket(socket); finish(null) }
                }
            }).use { manager ->
                val rule = rule(); assertTrue(manager.start(rule))
                val port = manager.states.value.single().localPort
                Socket("127.0.0.1", port).use {
                    await { endings.size == 1 }
                    Socket("127.0.0.1", port).use {
                        await { endings.size == 2 }
                        endings[1](error)
                        assertTrue("New pairing failure must preserve siblings", manager.states.value.single().running)
                        assertEquals(1, manager.states.value.single().connections)
                    }
                }
            }
        }
    }

    /** Probe the OS independently of the product's interface-discovery predicate. */
    private fun availableAddresses(): Set<InetAddress> {
        val result = mutableSetOf(InetAddress.getByName("127.0.0.1"))
        val v6 = InetAddress.getByName("::1")
        try { ServerSocket(0, 1, v6).use { result.add(v6) } } catch (_: java.net.SocketException) { }
        return result
    }

    private fun await(condition: () -> Boolean) {
        val limit = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < limit) Thread.sleep(10)
        assertTrue("Forwarding state timed out", condition())
    }
}
