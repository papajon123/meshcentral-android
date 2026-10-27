package ru.papajon.meshcentralmobile.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRevocationTest {
    private val saved = "abcdef01-2345-6789-abcd-ef0123456789"
    private val gone = "11111111-2222-3333-4444-555555555555"
    private val retained = setOf(saved)

    @Test fun savedTokenRemainsClickable() {
        val entries = listOf(ShortcutRevocation.Entry(ShortcutRevocation.PREFIX + saved, isEnabled = true))

        assertTrue(ShortcutRevocation.tokenOf(entries.single().id) == saved)
        assertTrue(ShortcutRevocation.revokedEnabled(entries, retained).isEmpty())
        assertTrue(ShortcutRevocation.reenabled(entries, retained).isEmpty())
    }

    @Test fun savedTokenIsReEnabledWhenItWasDisabled() {
        val id = ShortcutRevocation.PREFIX + saved

        assertEquals(listOf(id),
            ShortcutRevocation.reenabled(listOf(ShortcutRevocation.Entry(id, isEnabled = false)), retained))
        assertTrue(ShortcutRevocation.revokedEnabled(listOf(ShortcutRevocation.Entry(id, isEnabled = false)), retained).isEmpty())
    }

    @Test fun enabledRevokedTokenIsDisabled() {
        val id = ShortcutRevocation.PREFIX + gone

        assertEquals(listOf(id),
            ShortcutRevocation.revokedEnabled(listOf(ShortcutRevocation.Entry(id, isEnabled = true)), retained))
        assertTrue(ShortcutRevocation.reenabled(listOf(ShortcutRevocation.Entry(id, isEnabled = true)), retained).isEmpty())
    }

    @Test fun alreadyDisabledRevokedTokenIsNotDisabledAgain() {
        val id = ShortcutRevocation.PREFIX + gone
        val entries = listOf(ShortcutRevocation.Entry(id, isEnabled = false))

        assertTrue("Re-disabling would overwrite the original revocation reason",
            ShortcutRevocation.revokedEnabled(entries, retained).isEmpty())
        assertTrue(ShortcutRevocation.reenabled(entries, retained).isEmpty())
    }

    @Test fun malformedTokenIsRevokedWhenEnabledAndNeverReEnabled() {
        val malformed = listOf(
            ShortcutRevocation.PREFIX + "not-a-token",
            ShortcutRevocation.PREFIX,
            ShortcutRevocation.PREFIX + "abcdef01-2345-6789-abcd-ef012345678",
        )
        val entries = malformed.map { ShortcutRevocation.Entry(it, isEnabled = true) } +
            malformed.map { ShortcutRevocation.Entry(it, isEnabled = false) }

        assertEquals(malformed, ShortcutRevocation.revokedEnabled(entries, retained))
        assertTrue(ShortcutRevocation.reenabled(entries, retained).isEmpty())
        malformed.forEach {
            assertNull(ShortcutRevocation.tokenOf(it))
            assertFalse(ShortcutRevocation.isRetained(it, retained))
        }
    }

    @Test fun foreignShortcutIdIsIgnored() {
        val id = "favorites-" + saved
        val entries = listOf(ShortcutRevocation.Entry(id, isEnabled = true))

        assertNull(ShortcutRevocation.tokenOf(id))
        assertTrue(ShortcutRevocation.revokedEnabled(entries, retained).isEmpty())
        assertTrue(ShortcutRevocation.reenabled(entries, retained).isEmpty())
    }
}
