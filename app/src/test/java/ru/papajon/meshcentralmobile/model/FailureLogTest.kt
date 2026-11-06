package ru.papajon.meshcentralmobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class FailureLogTest {
    @Test fun describesClassChainWithoutMessages() {
        val failure = IllegalStateException(
            "wss://mesh.example/meshrelay.ashx?auth=secret-cookie",
            IOException("node//abc123 at /home/user/secret.txt"),
        )
        val text = FailureLog.describe(failure)
        assertEquals("IllegalStateException <- IOException", text)
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("mesh.example"))
    }

    @Test fun boundsLongAndSelfReferencingChains() {
        var chain: Throwable = RuntimeException("level 0")
        repeat(10) { chain = RuntimeException("level ${it + 1}", chain) }
        assertEquals(4, FailureLog.describe(chain).split(" <- ").size)
        val selfCaused = object : Exception("loop") { override val cause: Throwable get() = this }
        assertEquals(1, FailureLog.describe(selfCaused).split(" <- ").size)
    }

    @Test fun recordingDoesNotThrowOnTheJvm() {
        FailureLog.record("Test", "operation", IOException("hidden"))
        FailureLog.record("Test", "operation")
    }
}
