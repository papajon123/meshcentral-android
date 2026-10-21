package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshDevicePermissionsTest {
    private fun device(rights: Int?) = MeshDevice(
        nodeId = "node//example", meshId = "mesh//example", name = "Example computer",
        conn = 1, agentId = 1, rights = rights,
    )

    @Test fun desktopRestrictionOverridesBothControlAndViewGrants() {
        for (grant in listOf(MeshRights.REMOTE_CONTROL, MeshRights.REMOTE_VIEW, MeshRights.REMOTE_CONTROL or MeshRights.REMOTE_VIEW)) {
            val blocked = device(grant or MeshRights.NO_DESKTOP)
            assertFalse(blocked.hasDesktopPermission)
            assertFalse(blocked.canOpenNativeDesktop)
            assertFalse(blocked.inputAllowed)
            assertEquals(DesktopCapability.UnsupportedNoPermission, blocked.desktopCapability)
        }
    }

    @Test fun fullAdministratorRightsAreNotTreatedAsRestrictions() {
        val administrator = device(MeshRights.FULL)
        assertTrue(administrator.hasDesktopPermission)
        assertTrue(administrator.canOpenNativeDesktop)
        assertTrue(administrator.inputAllowed)
    }

    @Test fun unspecifiedRightsStillDeferAuthorizationToTheServer() {
        assertTrue(device(null).hasDesktopPermission)
        assertTrue(device(MeshRights.REMOTE_VIEW).canOpenNativeDesktop)
        assertFalse(device(MeshRights.REMOTE_VIEW).inputAllowed)
    }
}
