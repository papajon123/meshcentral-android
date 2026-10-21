package ru.papajon.meshcentralmobile.mesh

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshConnectionErrorTest {
    @Test fun dnsFailureHasAnActionableMessageWithoutRawDiagnostics() {
        val message = MeshConnectionError.describe(UnknownHostException("private-host?token=fixture"))
        assertTrue(message.contains("интернет"))
        assertTrue(message.contains("адрес"))
        assertFalse(message.contains("private-host"))
        assertFalse(message.contains("UnknownHostException"))
    }

    @Test fun unknownErrorsDoNotExposeTheRequest() {
        val message = MeshConnectionError.describe(IOException("https://user:secret@example.invalid/?auth=fixture"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("fixture"))
    }
}
