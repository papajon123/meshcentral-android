package ru.papajon.meshcentralmobile.mesh

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText

class MeshConnectionErrorTest {
    @Test fun dnsFailureHasAnActionableMessageWithoutRawDiagnostics() {
        val message = MeshConnectionError.describe(UnknownHostException("private-host?token=fixture"))
        assertEquals(R.string.runtime_error_host_not_found, (message as UiText.Resource).id)
        assertTrue(message.args.isEmpty())
    }

    @Test fun unknownErrorsDoNotExposeTheRequest() {
        val message = MeshConnectionError.describe(IOException("https://user:secret@example.invalid/?auth=fixture"))
        assertEquals(R.string.runtime_connection_lost, (message as UiText.Resource).id)
        assertTrue(message.args.isEmpty())
    }
}
