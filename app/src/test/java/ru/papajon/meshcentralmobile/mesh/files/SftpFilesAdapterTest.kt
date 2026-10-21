package ru.papajon.meshcentralmobile.mesh.files

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SftpFilesAdapterTest {
    @Test fun everyAuthenticationModeDisablesSaving() {
        val messages = listOf(
            SftpFilesAdapter.password("synthetic-user", "synthetic-password"),
            SftpFilesAdapter.privateKey("synthetic-user", "synthetic-key", "synthetic-passphrase"),
            SftpFilesAdapter.savedKey("synthetic-passphrase"),
        )
        messages.forEach { assertFalse(it.getBoolean("keep")) }
        assertEquals("sshauth", messages[0].getString("action"))
        assertEquals("sshkeyauth", messages[2].getString("action"))
        assertFalse(messages[1].has("password"))
    }

    @Test fun expectedSizeCoversEmptyAndMissingExactBlockEof() {
        assertTrue(SftpFilesAdapter.reachedExpectedSize(0, 0, false))
        assertTrue(SftpFilesAdapter.reachedExpectedSize(16_380, 16_380, false))
        assertFalse(SftpFilesAdapter.reachedExpectedSize(32_760, 16_380, false))
        assertTrue(SftpFilesAdapter.reachedExpectedSize(3, 3, true))
    }

    @Test fun changingSizesNeverProduceSuccess() {
        assertThrows(IllegalArgumentException::class.java) { SftpFilesAdapter.reachedExpectedSize(8, 4, true) }
        assertThrows(IllegalArgumentException::class.java) { SftpFilesAdapter.reachedExpectedSize(8, 9, false) }
        assertThrows(IllegalArgumentException::class.java) { SftpFilesAdapter.reachedExpectedSize(-1, 0, false) }
    }

    @Test fun streamedDigestRejectsOldFileOfIdenticalSize() {
        val sent = SftpFilesAdapter.digest().apply {
            update(byteArrayOf(0, 123)); update(byteArrayOf(42, 1))
        }.digest()
        val same = SftpFilesAdapter.digest().digest(byteArrayOf(0, 123, 42, 1))
        val oldSameLength = SftpFilesAdapter.digest().digest(byteArrayOf(0, 123, 42, 2))
        assertTrue(SftpFilesAdapter.matches(sent, same))
        assertFalse(SftpFilesAdapter.matches(sent, oldSameLength))
    }

    @Test fun absentNegativeOrFractionalSizeCannotBecomeAnEmptyDownload() {
        listOf("-1", "1.5", "9007199254740992", "\"0\"").forEach { size ->
            assertThrows(Exception::class.java) {
                SftpFilesAdapter.entries(JSONObject("""{"dir":[{"n":"file","t":3,"s":$size}]}"""))
            }
        }
        assertThrows(Exception::class.java) {
            SftpFilesAdapter.entries(JSONObject("""{"dir":[{"n":"file","t":3}]}"""))
        }
    }
}
