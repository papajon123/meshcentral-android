package ru.papajon.meshcentralmobile.mesh

import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import ru.papajon.meshcentralmobile.mesh.tls.ServerTls
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/** Opt-in local acceptance. Sign in via the real UI first; credentials stay inside SecureVault. */
class DeviceAdminLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun local131InvitationAndDeviceMutationsAreVerifiedAndRestored() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceAdminLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = SettingsRepository(context).activeProfile
        // Fail closed: this test must never connect to an owner's real server.
        check(profile.serverUrl.trimEnd('/') == "https://10.0.2.2:4443")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = MeshCentralClient(SecureVault(context, profile.id), scope)
        try {
            client.configureServer(profile.serverUrl)
            client.setPinnedServerKey(profile.pinnedSpkiSha256)
            check(client.loginWithStoredCredentials(profile.serverUrl)) { "Sign in to the local fixture first" }
            withTimeout(15000) { while (!client.clientState.value.devicesCurrent) delay(50) }
            check(client.clientState.value.userId == "user//admin")
            val state = client.clientState.value
            val original = state.devices.first { it.name == "Pixel Tablet" && it.conn == 0 && it.mtype == 2 }
            val source = state.groups.first { it.id == original.meshId }
            val target = state.groups.first { it.id != original.meshId && it.mtype == original.mtype }
            val oldNotes = client.readDeviceNotes(original.nodeId)
            val invitation = client.createDeviceInvitation(source.id, 1)
            val endpoint = profile.serverUrl.toHttpUrl()
            val info = client.clientState.value.serverCapabilities
            val expectedHost = info.advertisedName?.takeIf { it.contains('.') && (info.features ?: 0L) and 2L == 0L } ?: endpoint.host
            assertEquals(expectedHost, invitation.url.toHttpUrl().host)
            assertEquals(info.advertisedPort, invitation.url.toHttpUrl().port)
            check(invitation.url.toHttpUrl().host == "10.0.2.2" && invitation.url.toHttpUrl().port == 4443)
            compose.setContent {
                ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme {
                    ru.papajon.meshcentralmobile.ui.screens.management.InviteDialogContent(source.name, invitation, false, false, true, {}) {}
                }
            }
            compose.onNodeWithTag("admin_endpoint").performScrollTo().assertTextEquals(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.admin_endpoint, "$expectedHost:${info.advertisedPort}"))
            val http = OkHttpClient.Builder().followRedirects(false).apply { ServerTls(endpoint, profile.pinnedSpkiSha256).applyTo(this) }.build()
            try {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(invitation.url).build()).execute().use { response ->
                        assertTrue("Invitation page status", response.code == 200)
                        val html = response.body!!.string()
                        assertTrue("Agent installation page", html.contains("<title>Agent Installation</title>"))
                        assertTrue("Group-scoped invitation", html.contains(source.name))
                    }
                }
            } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
            try {
                client.editDevice(original.nodeId, "Device admin acceptance", "Local reversible check", "device-admin, acceptance")
                client.saveDeviceNotes(original.nodeId, "Device admin acceptance note")
                assertTrue("Note readback", client.readDeviceNotes(original.nodeId) == "Device admin acceptance note")
                client.moveDevice(original.nodeId, target.id)
                withTimeout(5000) { while (client.clientState.value.devices.first { it.nodeId == original.nodeId }.meshId != target.id) delay(25) }
                delay(300)
                withTimeout(10000) { while (!client.eventSnapshotRefreshIdle) delay(25) }
                val before = client.eventSnapshotRefreshCount
                repeat(20) { index ->
                    val destination = if (index % 2 == 0) source.id else target.id
                    val reply = client.requestControl(JSONObject().put("action", "changeDeviceMesh").put("meshid", destination)
                        .put("nodeids", JSONArray().put(original.nodeId)), "changeDeviceMesh", original.nodeId)
                    ru.papajon.meshcentralmobile.management.DeviceAdmin.requireSuccess(reply)
                }
                delay(300)
                withTimeout(10000) { while (!client.eventSnapshotRefreshIdle) delay(25) }
                val rounds = client.eventSnapshotRefreshCount - before
                assertTrue("Move burst must be coalesced", rounds in 1..2)
                File(context.cacheDir, "device-admin-live-metrics.txt").writeText("endpoint=${invitation.endpointLabel}\nmoves=20\neventRefreshRounds=$rounds\n")
            } finally {
                // Independent restores: a failed move rollback must not skip restoring metadata/notes.
                val failures = mutableListOf<Exception>()
                try {
                    val reply = client.requestControl(MeshControlMessages.nodes(), "nodes")
                    val actual = (MeshControlParser.parse(reply) as MeshControlEvent.Nodes).devices.first { it.nodeId == original.nodeId }
                    withTimeout(5000) { while (client.clientState.value.devices.first { it.nodeId == original.nodeId }.meshId != actual.meshId) delay(25) }
                    if (actual.meshId != original.meshId) client.moveDevice(original.nodeId, original.meshId)
                }
                catch (error: Exception) { failures.add(error) }
                try { client.editDevice(original.nodeId, original.name, original.description, original.tags.joinToString(",")) }
                catch (error: Exception) { failures.add(error) }
                try { client.saveDeviceNotes(original.nodeId, oldNotes) }
                catch (error: Exception) { failures.add(error) }
                check(failures.isEmpty()) { "Local fixture restoration needs attention" }
            }
            val restored = client.clientState.value.devices.first { it.nodeId == original.nodeId }
            assertTrue("Restored name, group, description and tags", restored.name == original.name && restored.meshId == original.meshId &&
                restored.description == original.description && restored.tags.sorted() == original.tags.sorted())
            assertTrue("Restored notes", client.readDeviceNotes(original.nodeId) == oldNotes)
        } finally { client.close(); scope.cancel() }
    }
}
