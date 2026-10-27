package ru.papajon.meshcentralmobile.forward

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class ListenerStopRaceTest {
    @Test fun stoppedRuleImmediatelyReleasesBothPortsWithoutReuseOrRetry() {
        val rule = PortForwardRule(profileId = "stop-test", nodeId = "node//stop", name = "Stop", remotePort = 22)
        PortForwardManager({ true }, { _, _, _, _ -> error("No connections") }).use { manager ->
            repeat(1000) {
                assertTrue(manager.start(rule))
                val port = manager.states.value.single().localPort
                val endpoints = manager.boundEndpoints(rule.id)
                // Let native accepts block; never sleep or retry after stop().
                Thread.sleep(2)
                manager.stop(rule.id)
                for (endpoint in endpoints) {
                    ServerSocket().use { probe ->
                        probe.reuseAddress = false
                        probe.bind(endpoint)
                        assertTrue(probe.isBound)
                    }
                }
                assertTrue(manager.start(rule.copy(localPort = port)))
                manager.stop(rule.id)
            }
        }
    }
}
