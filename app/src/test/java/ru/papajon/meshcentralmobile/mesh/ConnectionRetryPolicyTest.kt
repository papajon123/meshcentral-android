package ru.papajon.meshcentralmobile.mesh

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ConnectionRetryPolicyTest {
    @Test fun prolongedNetworkFailureIsBoundedAndDoesNotSpendServerRetries() {
        val policy = ConnectionRetryPolicy()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            List(7) { policy.nextDelay(true) })
        repeat(10_000) { assertEquals(30000L, policy.nextDelay(true)) }
        assertEquals(0, policy.serverRetries)
        assertFalse(policy.exhausted)
        repeat(5) { assertNotNull(policy.nextDelay(false)) }
        assertNull(policy.nextDelay(false))
        assertTrue(policy.exhausted)
    }

    @Test fun aNetworkChangeCannotReviveAnExhaustedServerBudget() {
        val policy = ConnectionRetryPolicy()
        repeat(6) { policy.nextDelay(false) }
        assertNull(policy.nextDelay(true))
        policy.reset()
        assertEquals(1000L, policy.nextDelay(false))
    }

    @Test fun networkFailuresDoNotEraseEarlierServerRefusals() {
        val policy = ConnectionRetryPolicy()
        repeat(4) { policy.nextDelay(false) }
        repeat(8) { policy.nextDelay(true) }
        assertEquals(16000L, policy.nextDelay(false))
        assertNull(policy.nextDelay(false))
    }

    @Test fun classifyOnlyTransportFailuresBeforeTheServerResponseAsNetworkFailures() {
        listOf(UnknownHostException(), ConnectException(), SocketTimeoutException(), SocketException(), EOFException()).forEach {
            assertTrue(ConnectionRetryPolicy.isNetworkFailure(it, null, false, true))
            assertFalse(ConnectionRetryPolicy.isNetworkFailure(it, 503, false, true))
            assertFalse(ConnectionRetryPolicy.isNetworkFailure(it, null, true, true))
            assertTrue(ConnectionRetryPolicy.isNetworkFailure(it, null, true, false))
        }
        assertFalse(ConnectionRetryPolicy.isNetworkFailure(SSLHandshakeException("fixture"), null, false, false))
        assertFalse(ConnectionRetryPolicy.isNetworkFailure(IllegalArgumentException(), null, false, true))
    }
}
