package ru.papajon.meshcentralmobile.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.papajon.meshcentralmobile.security.SecureVault

@RunWith(AndroidJUnit4::class)
class NotificationVaultTest {
    @Test
    fun scopedSecretIsKeystoreBackedAndProfileOriginBound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileA = "notif-vault-a-${System.nanoTime()}"
        val profileB = "notif-vault-b-${System.nanoTime()}"
        val vaultA = SecureVault(context, profileA)
        val vaultB = SecureVault(context, profileB)
        val payload = """{"tokenId":"mcn_0123456789abcdef","token":"${"A".repeat(43)}",""" +
            """"endpoint":"/d1/mobilecompanion/events","expiresAt":1900000000000,"userId":"user/d1/alice"}"""
        try {
            vaultA.saveScopedSecret("notifications", "https://mesh.example/d1/", payload)
            assertEquals(payload, vaultA.getScopedSecret("notifications", "https://mesh.example/d1/"))
            assertEquals(payload, vaultA.getScopedSecret("notifications", "https://mesh.example/d1"))
            assertNull(vaultA.getScopedSecret("notifications", "https://other.example/d1/"))
            assertNull(vaultB.getScopedSecret("notifications", "https://mesh.example/d1/"))
        } finally {
            vaultA.clearScopedSecret("notifications")
            vaultB.clearScopedSecret("notifications")
        }
        assertNull(vaultA.getScopedSecret("notifications", "https://mesh.example/d1/"))
    }
}
