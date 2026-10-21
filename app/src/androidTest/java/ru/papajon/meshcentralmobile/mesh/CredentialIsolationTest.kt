package ru.papajon.meshcentralmobile.mesh

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.security.SecureVault

class CredentialIsolationTest {
    @Test fun changingServerCannotSendSavedCredentialsToAnotherEndpoint() {
        withClient { client, vault, first, second ->
            vault.saveCredentials("fixture-user", "fixture-password", first.url("/team/").toString())
            assertNull(vault.getCredentials(second.url("/team/").toString()))
            assertFalse(client.loginWithStoredCredentials(second.url("/team/").toString()))
            assertFalse(client.loginWithStoredCredentials(first.url("/other/").toString()))
            assertNull(first.takeRequest(300, TimeUnit.MILLISECONDS))
            assertNull(second.takeRequest(300, TimeUnit.MILLISECONDS))
            assertNotNull(vault.getCredentials(first.url("/team/").toString()))
        }
    }

    @Test fun authenticatedControlRequestDoesNotFollowRedirectsAndPreservesPath() {
        withClient { client, _, first, second ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/control.ashx")))
            client.login(first.url("/team/").toString(), "fixture-user", "fixture-password")
            val request = first.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("/team/control.ashx", request!!.path)
            assertNotNull(request.getHeader("x-meshauth"))
            assertNull(second.takeRequest(500, TimeUnit.MILLISECONDS))
        }
    }

    private fun withClient(test: (MeshCentralClient, SecureVault, MockWebServer, MockWebServer) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("credential-isolation-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val first = MockWebServer()
        val second = MockWebServer()
        try {
            vault.clearCredentials()
            first.start()
            second.start()
            test(client, vault, first, second)
        } finally {
            client.close()
            scope.cancel()
            vault.clearCredentials()
            first.shutdown()
            second.shutdown()
        }
    }
}
