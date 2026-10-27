package ru.papajon.meshcentralmobile.mesh.files

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Explicit local-bench opt-in; uses a prior UI login in this dedicated emulator's Keystore. */
class ToolsPlusLiveTest {
    @Test fun thirtyFilesArchiveHashAndConfirmedGroupDeletion(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("toolsPlusLive") == "true")
        val root = requireNotNull(InstrumentationRegistry.getArguments().getString("toolsPlusDirectory"))
        val deviceName = requireNotNull(InstrumentationRegistry.getArguments().getString("toolsPlusDevice"))
        require(root.matches(Regex("/opt/meshcentral-livebench/fixture/tools-plus-[a-zA-Z0-9-]+")))
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val profile = settings.activeProfile
        require(profile.serverUrl.trimEnd('/') == "https://10.0.2.2:4443")
        require(profile.pinnedSpkiSha256 != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MeshCentralClient(SecureVault(context, profile.id), scope)
        var files: FilesController? = null
        FixtureDocumentsProvider.resetForTest(context)
        try {
            withContext(Dispatchers.Main) {
                client.configureServer(profile.serverUrl)
                client.setPinnedServerKey(profile.pinnedSpkiSha256)
                check(client.loginWithStoredCredentials(profile.serverUrl))
            }
            val state = withTimeout(45_000) { client.clientState.first { it.socketConnected && it.devicesCurrent && !it.requiresSignIn } }
            val device = state.devices.single { it.name == deviceName && it.conn and 1 != 0 }
            val controller = FilesController(client.createRelayConnection(device, RelayProtocol.AgentFiles), scope, context)
            files = controller
            controller.connect()
            withTimeout(30_000) { controller.state.first { it.connected && !it.busy } }
            controller.browse(root)
            withTimeout(30_000) { controller.state.first { it.path == root && !it.busy } }
            val source = controller.state.value.entries.single { it.name == "source" && it.directory }
            assertTrue(controller.batch.prepare(listOf(source)))
            val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + FixtureDocumentsProvider.AUTHORITY_SUFFIX, FixtureDocumentsProvider.ROOT_ID)
            controller.batch.chosen(tree)
            withTimeout(180_000) { controller.batch.state.first { it.rows.isNotEmpty() && !it.pending } }
            assertEquals("Archive must finish, including cleanup", DownloadStatus.Succeeded, controller.batch.state.value.rows.single().status)
            withTimeout(15_000) { controller.state.first { !it.batchBusy } }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, FixtureDocumentsProvider.ROOT_ID)
            val id = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("source.zip", it.getString(1)); val id = it.getString(0); assertFalse(it.moveToNext()); id
            }
            val expected = JSONObject(instrumentation.context.assets.open("tools-plus-manifest.json").bufferedReader().use { it.readText() })
            val found = mutableSetOf<String>()
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            ZipInputStream(context.contentResolver.openInputStream(document)!!).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.removePrefix("source/")
                    assertTrue("Unexpected archived path", expected.has(name))
                    assertTrue("Duplicate archive entry", found.add(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(32_768)
                    var size = 0L
                    while (true) { val n = zip.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); size += n }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(expected.getJSONObject(name).getLong("size"), size)
                    assertEquals(expected.getJSONObject(name).getString("sha256"), hash)
                }
            }
            assertEquals(30, found.size)
            val beforeDelete = controller.state.value.entries
            assertTrue(beforeDelete.none { it.name.startsWith("meshpilot-") && it.name.endsWith(".zip") })
            val selected = beforeDelete.filter { it.name in setOf("delete-a.txt", "delete-b.txt") }
            assertEquals(2, selected.size)
            controller.proposeDelete(selected)
            withTimeout(5000) { controller.state.first { it.confirmation != null } }
            assertEquals(2, controller.state.value.confirmation!!.entries.size)
            controller.confirm("")
            withTimeout(30_000) { controller.state.first { it.confirmation == null && !it.busy && !it.batchBusy && it.entries.none { entry -> entry.name in selected.map { file -> file.name } } } }
            assertTrue(controller.state.value.entries.any { it.name == "source" })
            File(context.getExternalFilesDir(null), "tools-plus/live-files.json").apply {
                parentFile!!.mkdirs()
                writeText("""{"sha256VerifiedFiles":30,"groupDeletedFiles":2,"temporaryArchiveRemoved":true}""")
            }
        } finally { files?.close(); client.close(); scope.cancel(); FixtureDocumentsProvider.resetForTest(context) }
    }
}
