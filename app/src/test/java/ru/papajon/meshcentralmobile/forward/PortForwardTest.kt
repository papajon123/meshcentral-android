package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.MeshRights

class PortForwardTest {
    private fun rule() = PortForwardRule(profileId = "profile", nodeId = "node//test", name = "Test", remotePort = 8080)

    @Test fun validatesPortsNamesAndRemoteHosts() {
        assertTrue(rule().valid())
        for (p in listOf(-1, 0, 65536)) assertFalse(rule().copy(remotePort = p).valid())
        for (p in listOf(-1, 65536)) assertFalse(rule().copy(localPort = p).valid())
        assertTrue(rule().copy(localPort = 65535, remotePort = 1).valid())
        for (host in listOf("https://example.com", "example.com/path", "a b", "a\nb", "user@host", "-host", "host-", "x..y", "fe80::1%wlan0", "[::1]"))
            assertFalse(host, rule().copy(remoteAddress = host).valid())
        for (host in listOf("", "127.0.0.1", "192.168.0.10", "server.local", "::1", "2001:db8::1"))
            assertTrue(host, rule().copy(remoteAddress = host).valid())
        assertFalse(rule().copy(name = "\n").valid())
    }

    @Test fun relayUrlPreservesDomainAndEncodesValues() {
        val url = tcpRelayUrl("https://example.test/domain/".toHttpUrl(), rule().copy(remoteAddress = "::1"), "synthetic+&cookie")
        assertEquals("/domain/meshrelay.ashx", url.encodedPath)
        assertEquals("synthetic+&cookie", url.queryParameter("auth"))
        assertEquals("node//test", url.queryParameter("nodeid"))
        assertEquals("14", url.queryParameter("p"))
        assertEquals("::1", url.queryParameter("tcpaddr"))
        assertNull(tcpRelayUrl("https://example.test/".toHttpUrl(), rule(), "fixture").queryParameter("tcpaddr"))
    }

    @Test fun templatesOnlyOpenLoopback() {
        assertEquals("rdp://full%20address=s:127.0.0.1:42345", ForwardKind.RDP.uri(42345))
        assertEquals("https://127.0.0.1:42345", ForwardKind.HTTPS.uri(42345))
        assertNull(ForwardKind.CUSTOM.uri(42345))
        assertEquals(8080, ForwardKind.HTTP_ALT.defaultPort)
    }

    @Test fun rightsAreFailClosedAndRelayRightIsSufficient() {
        val device = MeshDevice(nodeId = "node//test", meshId = "mesh//test", name = "Test", rights = MeshRights.REMOTE_CONTROL, mtype = 2, conn = 1)
        assertTrue(device.canForwardPorts())
        assertTrue(device.copy(rights = 0x00200000).canForwardPorts())
        assertTrue(device.copy(rights = MeshRights.FULL).canForwardPorts())
        assertFalse(device.copy(rights = null).canForwardPorts())
        assertFalse(device.copy(rights = MeshRights.REMOTE_VIEW).canForwardPorts())
        assertFalse(device.copy(mtype = 3).canForwardPorts())
        assertFalse(device.copy(conn = 0, connectivityKnown = true).canForwardPorts())
    }

    @Test fun listenersAreLoopbackOnlyAndPortsAreReleased() {
        PortForwardManager({ true }, { _, _, _, _ -> error("unused") }).use { manager ->
            assertTrue(manager.start(rule()))
            val current = manager.states.value.single()
            assertTrue(current.localPort > 0)
            manager.stop(current.rule.id)
            ServerSocket(current.localPort, 1, InetAddress.getByName("127.0.0.1")).use { assertTrue(it.isBound) }
            assertFalse(manager.states.value.single().running)
        }
    }

    @Test fun occupiedPortRuleLimitAndPermissionLoss() {
        var allowed = true
        PortForwardManager({ allowed }, { _, _, _, _ -> error("unused") }, maxRules = 1).use { manager ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
                assertFalse(manager.start(rule().copy(localPort = occupied.localPort)))
                assertEquals(ForwardError.PORT_IN_USE, manager.states.value.single().error)
            }
            val r = rule()
            assertTrue(manager.start(r))
            assertFalse(manager.start(rule()))
            assertEquals(ForwardError.LIMIT, manager.states.value.last().error)
            allowed = false
            manager.validateSession()
            assertFalse(manager.states.value.any { it.running })
            assertEquals(ForwardError.UNAVAILABLE, manager.states.value.first { it.rule.id == r.id }.error)
        }
    }

    @Test fun connectionLimitStopAndNoAutomaticRestart() {
        val opened = CopyOnWriteArrayList<Socket>()
        val closed = AtomicInteger()
        PortForwardManager({ true }, { _, socket, _, finish ->
            opened.add(socket)
            object : PortConnection {
                override fun start() = Unit
                override fun close() { socket.close(); closed.incrementAndGet(); finish(null) }
            }
        }, maxConnections = 1).use { manager ->
            val r = rule(); assertTrue(manager.start(r))
            val port = manager.states.value.single().localPort
            Socket("127.0.0.1", port).use { first ->
                await { opened.size == 1 }
                Socket("127.0.0.1", port).use { second ->
                    second.soTimeout = 2000
                    assertReset(second)
                }
                assertEquals(1, opened.size)
                manager.stopAll()
                first.soTimeout = 2000
                assertEquals(-1, first.getInputStream().read())
                assertEquals(1, closed.get())
                assertFalse(manager.states.value.single().running)
            }
        }
    }

    @Test fun transfersLargeBidirectionalStreamAndCountsBytes() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        val data = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val writer = Thread { fixture.peer.getOutputStream().write(data) }.apply { start() }
        val read = fixture.peer.getInputStream().readNBytes(data.size)
        writer.join(3000)
        assertArrayEquals(data, read)
        await { fixture.sent.get() == data.size.toLong() && fixture.received.get() == data.size.toLong() }
        assertEquals(0, fixture.factory.texts.get())
        assertTrue(fixture.factory.maxFrame.get() <= 16 * 1024)
    }

    @Test fun outgoingQueueAppliesBackpressureInsteadOfDroppingBytes() = withBridge { fixture ->
        fixture.factory.queued = TcpRelayBridge.MAX_QUEUED_BYTES
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.peer.getOutputStream().write(byteArrayOf(7, 8, 9))
        Thread.sleep(150)
        assertEquals(0, fixture.sent.get())
        fixture.factory.queued = 0
        assertArrayEquals(byteArrayOf(7, 8, 9), fixture.peer.getInputStream().readNBytes(3))
    }

    @Test fun localHalfCloseDrainsResponseThenTerminates() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.peer.getOutputStream().write(byteArrayOf(1, 2, 3))
        fixture.peer.shutdownOutput()
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.peer.getInputStream().readNBytes(3))
        assertEquals(-1, fixture.peer.getInputStream().read())
        await { fixture.finished.get() == 1 }
    }

    @Test fun localTcpResetIsNotAServerRefusal() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.peer.setSoLinger(true, 0)
        fixture.peer.close()
        await { fixture.finished.get() == 1 }
        assertNull("A local reset must not terminate the whole rule", fixture.error)
    }

    @Test fun oneClientClosingLeavesTheListenerAndSiblingAlive() {
        val clients = CopyOnWriteArrayList<Socket>()
        val completions = CopyOnWriteArrayList<() -> Unit>()
        PortForwardManager({ true }, { _, socket, _, finished ->
            clients.add(socket)
            val c = object : PortConnection {
                override fun start() = Unit
                override fun close() { socket.close(); finished(null) }
            }
            completions.add { c.close() }; c
        }).use { manager ->
            manager.start(rule())
            val port = manager.states.value.single().localPort
            Socket("127.0.0.1", port).use { first ->
                Socket("127.0.0.1", port).use { second ->
                    await { completions.size == 2 }
                    completions[0]()
                    assertTrue(manager.states.value.single().running)
                    assertEquals(1, manager.states.value.single().connections)
                    assertFalse(clients[1].isClosed)
                    second.getOutputStream().write(42)
                    assertEquals(42, clients[1].getInputStream().read())
                }
            }
        }
    }

    @Test fun storedRulesRejectCorruptionUnknownSchemaAndPortOverflow() {
        assertTrue(PortForwardCodec.decode("{broken").isEmpty())
        assertTrue(PortForwardCodec.decode("{\"schema\":2,\"rules\":[]}").isEmpty())
        val encoded = PortForwardCodec.encode(listOf(rule()))
        assertEquals(1, PortForwardCodec.decode(encoded).size)
        for (port in listOf(4294967297L, 22.5, "22", -1)) {
            val root = org.json.JSONObject(encoded)
            root.getJSONArray("rules").getJSONObject(0).put("remotePort", port)
            assertTrue(PortForwardCodec.decode(root.toString()).isEmpty())
        }
    }

    @Test fun rejectionClosesLocalSocketWithoutRetryOrPayload() = withBridge { fixture ->
        fixture.factory.listener.onClosing(fixture.factory.socket, 1005, "private reason must not leak")
        assertReset(fixture.peer)
        assertEquals(ForwardError.CONNECT_FAILED, fixture.error)
        assertEquals(1, fixture.factory.opens.get())
        assertEquals(0, fixture.sent.get())
    }

    @Test fun oversizedIncomingFrameClosesWithoutWritingItToTcp() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.listener.onMessage(fixture.factory.socket, ByteArray(TcpRelayBridge.MAX_FRAME_BYTES + 1).toByteString())
        assertEquals(ForwardError.LIMIT, fixture.error)
        assertReset(fixture.peer)
        assertEquals(0, fixture.received.get())
    }

    @Test fun remoteFailureAndExplicitStopCloseBothSidesExactlyOnce() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.listener.onFailure(fixture.factory.socket, java.io.IOException("secret-url"), null)
        fixture.bridge.close()
        assertReset(fixture.peer)
        assertEquals(1, fixture.finished.get())
        assertEquals(ForwardError.CONNECTION_LOST, fixture.error)
        assertTrue(fixture.factory.cancelled.get())
    }

    @Test fun failedActiveConnectionDoesNotStopSiblingOrListener() {
        val sockets = CopyOnWriteArrayList<Socket>()
        val finishers = CopyOnWriteArrayList<(ForwardError?) -> Unit>()
        PortForwardManager({ true }, { _, socket, _, finished ->
            sockets.add(socket); finishers.add(finished)
            object : PortConnection {
                override fun start() = Unit
                override fun close() { socket.close(); finished(null) }
            }
        }).use { manager ->
            manager.start(rule())
            val port = manager.states.value.single().localPort
            Socket("127.0.0.1", port).use { first ->
                Socket("127.0.0.1", port).use { second ->
                    await { finishers.size == 2 }
                    sockets[0].close(); finishers[0](ForwardError.CONNECTION_LOST)
                    assertTrue(manager.states.value.single().running)
                    assertEquals(1, manager.states.value.single().connections)
                    second.getOutputStream().write(73)
                    assertEquals(73, sockets[1].getInputStream().read())
                }
            }
        }
    }

    @Test fun halfClosedRequestReceivesResponseLastingLongerThanFiveSeconds() = withBridge(drainMillis = 5000) { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.peer.shutdownOutput()
        val responder = Thread {
            repeat(9) { Thread.sleep(700); fixture.factory.listener.onMessage(fixture.factory.socket, byteArrayOf(it.toByte()).toByteString()) }
        }.apply { isDaemon = true; start() }
        val received = fixture.peer.getInputStream().readNBytes(9)
        responder.join(8000)
        assertArrayEquals(ByteArray(9) { it.toByte() }, received)
        assertEquals(0, fixture.finished.get())
    }

    @Test fun handshakeTimesOutAndLateCallbackCannotReviveConnection() = withBridge { fixture ->
        await { fixture.finished.get() == 1 }
        assertEquals(ForwardError.TIMEOUT, fixture.error)
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        assertReset(fixture.peer)
        assertEquals(0, fixture.sent.get())
    }

    @Test fun pairedRelayDoesNotProveTargetReachableOrInventNoDataTimeout() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        Thread.sleep(750) // Longer than the fixture's pairing deadline, without any target bytes.
        assertEquals(0, fixture.finished.get())
        fixture.peer.soTimeout = 100
        try { fixture.peer.getInputStream().read(); fail("An unavailable target must not look like EOF") }
        catch (_: java.net.SocketTimeoutException) { }
        fixture.bridge.close()
        assertReset(fixture.peer)
    }

    @Test fun serverErrorCloseAfterPairingIsNotSuccessfulEof() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.listener.onClosing(fixture.factory.socket, 1011, "fixture error")
        assertReset(fixture.peer)
        assertEquals(ForwardError.CONNECTION_LOST, fixture.error)
    }

    @Test fun localEofWaitsForOutgoingQueueThenClosesGracefully() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.queued = 1024
        fixture.peer.shutdownOutput()
        Thread.sleep(350)
        assertEquals("EOF must not discard queued upload bytes", 0, fixture.finished.get())
        fixture.factory.queued = 0
        await { fixture.finished.get() == 1 }
        assertEquals(-1, fixture.peer.getInputStream().read())
        assertTrue(fixture.factory.closed.get())
        assertFalse("Normal EOF must use a WebSocket close handshake", fixture.factory.cancelled.get())
    }

    @Test fun progressingUploadOutlivesTheInactivityBudgetAfterEof() = withBridge(uploadDrainMillis = 500) { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.queued = 15
        fixture.peer.shutdownOutput()
        for (remaining in 14 downTo 1) {
            Thread.sleep(100)
            fixture.factory.queued = remaining.toLong()
            assertEquals("Progress must renew the stall deadline", 0, fixture.finished.get())
        }
        fixture.factory.queued = 0
        await { fixture.finished.get() == 1 }
        assertEquals(-1, fixture.peer.getInputStream().read())
        assertTrue(fixture.factory.closed.get())
        assertNull(fixture.error)
    }

    @Test fun queuedUploadExpiryResetsInsteadOfReportingSuccessfulEof() = withBridge(uploadDrainMillis = 80) { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.queued = 1
        fixture.peer.shutdownOutput()
        assertReset(fixture.peer)
        await { fixture.finished.get() == 1 }
        assertEquals(ForwardError.CONNECTION_LOST, fixture.error)
        assertTrue(fixture.factory.cancelled.get())
    }

    @Test fun remoteEofWithUnsentUploadIsAnError() = withBridge { fixture ->
        fixture.factory.listener.onMessage(fixture.factory.socket, "c")
        fixture.factory.queued = 1
        fixture.factory.listener.onClosing(fixture.factory.socket, 1000, "")
        assertReset(fixture.peer)
        assertEquals(ForwardError.CONNECTION_LOST, fixture.error)
    }

    @Test fun congestedWriterUsesIncreasingBoundedPollingDelays() {
        val pauses = CopyOnWriteArrayList<Long>()
        withBridge(pause = { pauses.add(it); kotlinx.coroutines.delay(it) }) { fixture ->
            fixture.factory.listener.onMessage(fixture.factory.socket, "c")
            fixture.factory.queued = TcpRelayBridge.MAX_QUEUED_BYTES
            fixture.peer.getOutputStream().write(42)
            await { pauses.size >= 7 }
            assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 100L, 100L), pauses.take(7))
            assertEquals(0, fixture.factory.maxFrame.get())
            fixture.factory.queued = 0
            assertEquals(42, fixture.peer.getInputStream().read())
        }
    }

    private class FakeFactory : WebSocket.Factory {
        lateinit var listener: WebSocketListener
        val opens = AtomicInteger(); val texts = AtomicInteger(); val maxFrame = AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile var queued = 0L
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("https://example.test/").build()
            override fun queueSize() = queued
            override fun send(text: String): Boolean { texts.incrementAndGet(); return true }
            override fun send(bytes: ByteString): Boolean {
                maxFrame.updateAndGet { maxOf(it, bytes.size) }
                listener.onMessage(this, bytes)
                return true
            }
            override fun close(code: Int, reason: String?): Boolean { closed.set(true); return true }
            override fun cancel() { cancelled.set(true) }
        }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener; opens.incrementAndGet(); return socket
        }
    }
    private class Fixture(val peer: Socket, val bridge: TcpRelayBridge, val factory: FakeFactory,
        val sent: AtomicLong, val received: AtomicLong, val finished: AtomicInteger) {
        @Volatile var error: ForwardError? = null
    }
    private fun withBridge(drainMillis: Long = 100, uploadDrainMillis: Long = 120_000,
        pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }, block: (Fixture) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val peer = Socket("127.0.0.1", server.localPort).apply { soTimeout = 3000 }
        val local = server.accept(); server.close()
        val factory = FakeFactory(); val sent = AtomicLong(); val received = AtomicLong(); val finished = AtomicInteger()
        lateinit var f: Fixture
        val bridge = TcpRelayBridge(local, factory, Request.Builder().url("https://example.test/").build(), scope, { true },
            { s, r -> sent.addAndGet(s); received.addAndGet(r); Unit }, { f.error = it; finished.incrementAndGet() },
            handshakeMillis = 500, drainMillis = drainMillis, uploadDrainMillis = uploadDrainMillis, pause = pause)
        f = Fixture(peer, bridge, factory, sent, received, finished)
        try { bridge.start(); block(f) } finally { bridge.close(); peer.close(); scope.cancel() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Condition timed out", condition())
    }
    private fun assertReset(socket: Socket) {
        try { while (socket.getInputStream().read() != -1) { }; fail("Expected TCP reset, not clean EOF") }
        catch (_: java.net.SocketException) { }
    }
}
