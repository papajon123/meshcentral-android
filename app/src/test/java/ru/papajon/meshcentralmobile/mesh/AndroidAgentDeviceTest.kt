package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.mesh.session.SessionAvailability
import ru.papajon.meshcentralmobile.model.ConnectionState

class AndroidAgentDeviceTest {
    private val phone = MeshDevice("node//phone", "mesh//test", "Test phone", conn = 1,
        mtype = 2, agentId = 14, agentCaps = 13, rights = MeshRights.REMOTE_CONTROL)

    @Test fun officialPhoneIsRecognizedAndCapsDriveTools() {
        assertTrue(phone.isAndroidAgent)
        assertFalse(phone.copy(agentId = 6).isAndroidAgent)
        assertFalse(phone.copy(agentId = null).isAndroidAgent)
        assertTrue(phone.canOpenNativeDesktop)
        assertEquals(SessionAvailability.Available, phone.sessionAvailability(RelayProtocol.AgentFiles))
        assertEquals(SessionAvailability.Unsupported, phone.sessionAvailability(RelayProtocol.AgentTerminal))
    }

    @Test fun phoneNeverEnablesRemoteInputEvenForAdministrator() {
        assertFalse(phone.supportsRemoteInput)
        assertFalse(phone.copy(rights = MeshRights.FULL).inputAllowed)
        assertFalse(RemoteDesktopState(device = phone, connectionState = ConnectionState.Connected,
            handshakeComplete = true).inputEnabled)
        assertTrue(phone.copy(agentId = 6).supportsRemoteInput)
    }

    @Test fun portraitViewportFillsWidthAndCanPanVertically() {
        val viewport = RemoteViewport(400, 600, 1080, 2400, fitWidth = true)
        assertEquals(400f, viewport.drawnWidth, 0.01f)
        assertEquals(888.89f, viewport.drawnHeight, 0.01f)
        val zoomed = viewport.zoomAround(200f, 600f, 2f)
        assertEquals(-300f, zoomed.panY, 0.01f)
    }
}
