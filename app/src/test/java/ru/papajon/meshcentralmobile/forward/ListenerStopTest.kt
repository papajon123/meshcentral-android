package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ListenerStopTest {
    @Test fun ipv4OnlyHostStillBindsExclusivelyToIpv4Loopback() {
        val listeners = LoopbackListeners.bind(0, 2, ipv6Available = false)
        try {
            val listener = listeners.single()
            assertEquals(InetAddress.getByName("127.0.0.1"), listener.inetAddress)
            assertFalse(listener.inetAddress.isAnyLocalAddress)
            try {
                LoopbackListeners.bind(listener.localPort, 2, ipv6Available = false).forEach { it.close() }
                fail("IPv4-only mode must not steal an occupied port")
            } catch (_: java.net.BindException) { }
        } finally { listeners.forEach { it.close() } }
    }

    @Test fun closeWaitsForAcceptEvenWhenCallerIsInterrupted() {
        val entered = CountDownLatch(1)
        val closeCalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val restoredInterrupt = AtomicBoolean()
        val socket = object : ServerSocket() {
            override fun accept(): Socket {
                entered.countDown(); release.await()
                throw SocketException("Native accept returned after close")
            }
            override fun close() { closeCalled.countDown(); super.close() }
        }
        val listener = LoopbackListener(socket)
        val acceptor = Thread { try { listener.accept() } catch (_: SocketException) { } }
        val stopper = Thread {
            Thread.currentThread().interrupt()
            listener.close()
            restoredInterrupt.set(Thread.currentThread().isInterrupted)
            closed.countDown()
        }
        try {
            acceptor.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            stopper.start(); assertTrue(closeCalled.await(3, TimeUnit.SECONDS))
            assertFalse("Logical socket close is not yet native accept completion", closed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(restoredInterrupt.get())
        } finally { release.countDown(); acceptor.join(3000); stopper.join(3000); listener.close() }
    }

}
