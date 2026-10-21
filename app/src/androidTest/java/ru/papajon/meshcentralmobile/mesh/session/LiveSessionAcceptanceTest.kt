package ru.papajon.meshcentralmobile.mesh.session

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.mesh.files.FilesController
import ru.papajon.meshcentralmobile.security.SecureVault

/**
 * Explicit opt-in only: -e liveFixture http://10.0.2.2:19847/fixture.
 * Uses the isolated local Linux fixture, never an app-configured or production server.
 * file:// is only a ContentResolver stream fixture here; this does not test the SAF picker UI.
 */
class LiveSessionAcceptanceTest {
    private lateinit var context: Context
    private lateinit var fixture: Fixture
    private lateinit var scope: CoroutineScope
    private lateinit var vault: SecureVault
    private lateinit var client: MeshCentralClient
    private val localFiles = mutableListOf<File>()
    private val remoteFiles = mutableListOf<Pair<RelayProtocol, String>>()
    private val connections = mutableListOf<RelayConnection>()
    private val controllers = mutableListOf<FilesController>()
    private var diagnosticFiles: FilesController? = null
    private var stage = "bootstrap"

    // No data class: avoid a generated toString containing fixture credentials.
    private class Fixture(json: JSONObject) {
        val serverUrl = json.getString("serverUrl")
        val username = json.getString("username")
        val password = json.getString("password")
        val agentNodeId = json.getString("agentNodeId")
        val sshNodeId = json.getString("sshNodeId")
        val sshUsername = json.getString("sshUsername")
        val sshPassword = json.getString("sshPassword")
        val directory = json.getString("testDirectory")
    }

    @Before fun setup() {
        val argument = InstrumentationRegistry.getArguments().getString("liveFixture")
        assumeTrue("Live fixture argument is absent or is not the isolated emulator fixture", argument == FIXTURE_URL)
        assumeTrue("Live fixture requires a debug build", BuildConfig.DEBUG)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        safeRun {
            val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
            try {
                http.newCall(Request.Builder().url(FIXTURE_URL).build()).execute().use { response ->
                    assertTrue("Fixture bootstrap was not ready", response.code == 200)
                    val body = requireNotNull(response.body)
                    assertTrue("Unexpected fixture response size", body.contentLength() in 1..16_384)
                    fixture = Fixture(JSONObject(body.string()))
                }
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
            assertTrue("Fixture server is outside the isolated emulator endpoint", fixture.serverUrl == FIXTURE_SERVER)
            assertTrue("Fixture directory is outside the test directory", fixture.directory == TEST_DIRECTORY)
            assertTrue("Fixture device identifiers are invalid", fixture.agentNodeId.startsWith("node//") && fixture.sshNodeId.startsWith("node//"))
            // Separate test preferences preserve any credentials in the debug app's ordinary vault.
            val isolatedContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("live-fixture-$name", mode)
            }
            vault = SecureVault(isolatedContext)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            client = MeshCentralClient(vault, scope)
            client.setAllowSelfSignedCertificates(true)
            client.login(fixture.serverUrl, fixture.username, fixture.password)
            stage = "authenticated device snapshot"
            withTimeout(45_000) {
                client.clientState.first { state ->
                    state.socketConnected && !state.requiresSignIn && state.devicesCurrent &&
                        state.devices.any { it.nodeId == fixture.agentNodeId } &&
                        state.devices.any { it.nodeId == fixture.sshNodeId }
                }
            }
        }
    }

    @After fun cleanup() {
        if (::client.isInitialized) {
            try {
                controllers.forEach { it.close() }
                connections.forEach { it.close() }
                if (remoteFiles.isNotEmpty()) safeRun {
                    stage = "remove only this test's fixture files"
                    for ((protocol, files) in remoteFiles.groupBy({ it.first }, { it.second })) {
                        val paths = files.map { "$TEST_DIRECTORY/$it" }
                        assertTrue("Cleanup path escaped its fixture directory", files.all { it.startsWith("android-live-") && '/' !in it && '\\' !in it })
                        val script = "import pathlib\nfor p in ${JSONObject.quote(paths.joinToString("\n"))}.splitlines():\n pathlib.Path(p).unlink(missing_ok=True)"
                        runPython(protocol, script)
                    }
                }
            } finally {
                client.logout(clearStoredCredentials = false)
                client.close()
                vault.clearCredentials()
                scope.cancel()
            }
        }
        localFiles.forEach { it.delete() }
    }

    @Test fun agentTerminalExecutesUniqueMarker() = safeRun {
        stage = "Agent terminal marker"
        runPython(RelayProtocol.AgentTerminal, "pass")
    }

    @Test fun sshTerminalAuthenticatesWithoutSavingAndWaitsForShell() = safeRun {
        stage = "SSH terminal marker after shell output"
        runPython(RelayProtocol.SshTerminal, "pass")
    }

    @Test fun agentFilesBrowseUploadAndDownloadBytes() = safeRun {
        exerciseFiles(sftp = false)
    }

    @Test fun sftpMarkerEmptyExactBlockAndUploadReadback() = safeRun {
        exerciseFiles(sftp = true)
    }

    private suspend fun exerciseFiles(sftp: Boolean) {
        val terminalProtocol = if (sftp) RelayProtocol.SshTerminal else RelayProtocol.AgentTerminal
        val fileProtocol = if (sftp) RelayProtocol.SshFiles else RelayProtocol.AgentFiles
        val prefix = "android-live-${UUID.randomUUID().toString().replace("-", "")}-"
        val markerName = prefix + "маркер.txt"
        val emptyName = prefix + "empty.bin"
        val exactName = prefix + "exact.bin"
        val uploadName = prefix + "upload-юникод.bin"
        val names = listOf(markerName, emptyName, exactName, uploadName)
        names.forEach { remoteFiles += terminalProtocol to it }
        val markerBytes = "isolated Android fixture marker\n".toByteArray(Charsets.UTF_8)
        val marker64 = Base64.encodeToString(markerBytes, Base64.NO_WRAP)
        stage = "$fileProtocol seed owned fixture files"
        runPython(terminalProtocol, """
            import pathlib, base64
            root = pathlib.Path('$TEST_DIRECTORY')
            (root / '$markerName').write_bytes(base64.b64decode('$marker64'))
            (root / '$emptyName').write_bytes(b'')
            (root / '$exactName').write_bytes(bytes((i % 256 for i in range(16380))))
        """.trimIndent())

        val connection = create(fileProtocol)
        val files = FilesController(connection, scope, context).also { controllers += it; diagnosticFiles = it }
        files.connect()
        if (sftp) {
            stage = "SFTP authentication request"
            await { files.state.value.authRequired }
            files.authenticatePassword(fixture.sshUsername, fixture.sshPassword)
        }
        stage = "$fileProtocol initial directory"
        await { files.state.value.connected && !files.state.value.busy }
        files.browse(TEST_DIRECTORY)
        await { files.state.value.connected && !files.state.value.busy && files.state.value.path == TEST_DIRECTORY }
        assertTrue("Owned marker missing from live directory response", files.state.value.entries.any { it.name == markerName })

        downloadAndCheck(files, markerName, markerBytes, "$fileProtocol marker download")
        if (sftp) {
            downloadAndCheck(files, emptyName, byteArrayOf(), "SFTP zero-byte download")
            downloadAndCheck(files, exactName, ByteArray(16_380) { (it % 256).toByte() }, "SFTP exact-block download")
        }

        stage = "$fileProtocol upload confirmation"
        val payload = ByteArray(3 * 16_380 + 19) { (it % 256).toByte() }.also { it[0] = '{'.code.toByte() }
        val source = localFile().apply { writeBytes(payload) }
        files.proposeUpload(Uri.fromFile(source))
        await { files.state.value.confirmation != null }
        files.confirm(uploadName)
        stage = "$fileProtocol upload and server verification"
        val success = R.string.files_sftp_upload_verified // Both adapters require verified SHA-256 readback.
        await {
            files.state.value.connected && !files.state.value.busy &&
                files.state.value.message == UiText.resource(success)
        }
        assertTrue("Uploaded file was not verified in the live directory", files.state.value.entries.any { it.name == uploadName && it.size == payload.size.toLong() })
        // Independently re-download and compare SHA-256 even after the SFTP controller's own readback.
        downloadAndCheck(files, uploadName, payload, "$fileProtocol independent upload readback")
    }

    private suspend fun downloadAndCheck(files: FilesController, name: String, expected: ByteArray, description: String) {
        stage = "$description refresh"
        // Reset the previous completion message before waiting for the next operation's completion.
        if (files.state.value.message != null) {
            files.refresh()
            await { files.state.value.connected && !files.state.value.busy && files.state.value.message == null }
        }
        stage = description
        val destination = localFile()
        files.download("$TEST_DIRECTORY/$name", name, Uri.fromFile(destination))
        await {
            files.state.value.connected && !files.state.value.busy &&
                files.state.value.message == UiText.resource(R.string.files_download_complete)
        }
        assertTrue("Downloaded fixture byte count differs", destination.length() == expected.size.toLong())
        assertTrue("Downloaded fixture SHA-256 differs", MessageDigest.isEqual(sha256(expected), sha256(destination.readBytes())))
    }

    private fun create(protocol: RelayProtocol): RelayConnection {
        val nodeId = if (protocol == RelayProtocol.SshFiles || protocol == RelayProtocol.SshTerminal) fixture.sshNodeId else fixture.agentNodeId
        val device: MeshDevice = client.clientState.value.devices.single { it.nodeId == nodeId }
        return client.createRelayConnection(device, protocol, JSONObject().put("cols", 80).put("rows", 24)).also { connections += it }
    }

    private suspend fun runPython(protocol: RelayProtocol, script: String) {
        val relay = create(protocol)
        val frames = Channel<RelayFrame>(16)
        val reader: Job = scope.launch {
            try { relay.incoming.collect { frames.send(it) } } finally { frames.close() }
        }
        try {
            relay.connect()
            withTimeout(35_000) { relay.state.first { it.phase == RelayPhase.Connected } }
            if (protocol == RelayProtocol.SshTerminal) {
                var requested = false
                withTimeout(30_000) {
                    while (!requested) {
                        val raw = text(frames.receive())
                        if (raw.startsWith("{")) {
                            val event = JSONObject(raw)
                            assertTrue("Fixture SSH unexpectedly requested a saved key", !event.optBoolean("askkeypass"))
                            requested = event.optString("action") == "sshauth"
                        }
                    }
                }
                val auth = JSONObject().put("action", "sshauth").put("username", fixture.sshUsername)
                    .put("password", fixture.sshPassword).put("keep", false)
                    .put("cols", 80).put("rows", 24).put("width", 800).put("height", 480)
                assertTrue("SSH authentication frame was not accepted by the socket", relay.sendText(auth.toString()))
                withTimeout(30_000) {
                    var authenticated = false
                    while (true) {
                        val raw = text(frames.receive())
                        if (raw == "c" || raw == "cr") authenticated = true
                        if (authenticated && raw.startsWith("~")) break
                        if (raw.startsWith("{")) {
                            val action = JSONObject(raw).optString("action")
                            assertTrue("Live SSH authentication or connection failed", action !in setOf("autherror", "connectionerror", "sessiontimeout"))
                        }
                    }
                }
            }
            val markerId = UUID.randomUUID().toString().replace("-", "")
            val marker = "MC_ANDROID_$markerId"
            val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val command = "python3 -c \"import base64;exec(base64.b64decode('$encoded'))\" && printf 'MC_ANDROID_%s\\n' '$markerId'\r"
            val accepted = if (protocol == RelayProtocol.SshTerminal) relay.sendText("~$command") else relay.sendBinary(command.toByteArray(Charsets.UTF_8))
            assertTrue("Terminal command was not accepted by the socket", accepted)
            withTimeout(30_000) {
                val output = StringBuilder()
                while (!output.contains(marker)) {
                    output.append(text(frames.receive()))
                    assertTrue("Fixture terminal exceeded output bound", output.length <= 65_536)
                }
            }
        } finally {
            relay.close()
            reader.cancel()
            frames.cancel()
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(45_000) { while (!predicate()) delay(20) }
    }
    private fun localFile() = File.createTempFile("android-live-", ".bin", context.cacheDir).also { localFiles += it }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun text(frame: RelayFrame): String = when (frame) {
        is RelayFrame.Text -> frame.value
        is RelayFrame.Binary -> frame.value.toString(Charsets.UTF_8)
    }
    private fun safeRun(block: suspend () -> Unit) = runBlocking {
        try { block() }
        catch (failure: Exception) {
            // Deliberately omit exception messages and causes: socket exceptions may contain auth URLs.
            val resource = diagnosticFiles?.state?.value?.message as? UiText.Resource
            val reason = resource?.let { context.resources.getResourceEntryName(it.id) } ?: "no-file-status"
            throw AssertionError("Live fixture step failed: $stage (${failure.javaClass.simpleName}; $reason)")
        }
    }

    companion object {
        private const val FIXTURE_URL = "http://10.0.2.2:19847/fixture"
        private const val FIXTURE_SERVER = "https://10.0.2.2:19846"
        private const val TEST_DIRECTORY = "/tmp/mesh-fixture"
    }
}
