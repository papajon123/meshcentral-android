package ru.papajon.meshcentralmobile.mesh.files

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.session.*

class AndroidFilesConnectionTest {
    @Test fun ambiguousStandaloneZeroFailsSizeCheckRatherThanInventingFileContents() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/zero","id":8,"size":1}""")
            data.input.send(RelayFrame.Binary(byteArrayOf(0))); data.input.close()
            val frame = withTimeout(2000) { adapter.incoming.first() } as RelayFrame.Text
            assertEquals("cancel", JSONObject(frame.value).getString("sub"))
        } finally { adapter.close(); scope.cancel() }
    }
    @Test fun releasedAgentStreamsWithoutHeaderAcrossKeepalivesAndPong() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> errors += e })
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"Sdcard/test","id":7,"size":6}""")
            val result = async { withTimeout(3000) { adapter.incoming.take(4).toList() } }
            raw.input.send(RelayFrame.Binary(byteArrayOf(0)))
            data.input.send(RelayFrame.Binary(byteArrayOf(123, 0, 42)))
            data.input.send(RelayFrame.Binary(byteArrayOf(0)))
            data.input.send(RelayFrame.Text("""{"ctrlChannel":"102938","type":"pong"}"""))
            data.input.send(RelayFrame.Binary(byteArrayOf(3, 2, 1)))
            data.input.close()
            val frames = result.await()
            assertEquals("start", JSONObject((frames.first() as RelayFrame.Text).value).getString("sub"))
            assertArrayEquals(byteArrayOf(123, 0, 42, 3, 2, 1), frames.drop(1).flatMap { (it as RelayFrame.Binary).value.drop(4) }.toByteArray())
            assertTrue(AgentFilesProtocol.downloadFinal((frames.last() as RelayFrame.Binary).value))
            assertTrue(errors.isEmpty())
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun p5KeepaliveDoesNotReachUploadConsumer() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { error("No download") }
        try {
            adapter.connect()
            raw.input.send(RelayFrame.Binary(byteArrayOf(0)))
            raw.input.send(RelayFrame.Binary("""{"action":"uploadack","reqid":3}""".toByteArray()))
            val frame = withTimeout(2000) { adapter.incoming.first() } as RelayFrame.Binary
            assertEquals("uploadack", JSONObject(frame.value.toString(Charsets.UTF_8)).getString("action"))
        } finally { adapter.close(); scope.cancel() }
    }

    private class Fake(override val protocol: RelayProtocol) : RelayConnection {
        override val state = MutableStateFlow(RelayStatus(RelayPhase.Connected))
        val input = Channel<RelayFrame>(16)
        override val incoming = input.receiveAsFlow()
        val binary = mutableListOf<ByteArray>()
        override fun connect() {}
        override fun sendText(value: String): Boolean = error("Android p5 expects binary JSON")
        override fun sendBinary(value: ByteArray): Boolean { binary += value; return true }
        override fun close() { input.close() }
    }

    @Test fun p5UsesBinaryJsonAndRelativeVirtualRoots() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { error("No download expected") }
        try {
            adapter.connect()
            assertTrue(adapter.sendText("""{"action":"ls","path":"/Images","reqid":7}"""))
            assertTrue(JSONObject(raw.binary.single().toString(Charsets.UTF_8)).similar(JSONObject("""{"action":"ls","path":"Images","reqid":7}""")))
            raw.input.send(RelayFrame.Binary("""{"action":"ls","path":"Images","reqid":7,"dir":[]}""".toByteArray()))
            val response = withTimeout(2000) { adapter.incoming.first() } as RelayFrame.Text
            assertEquals("Images", JSONObject(response.value).getString("path"))
            assertFalse(adapter.sendText("""{"action":"mkdir","path":"/Sdcard/test"}"""))
            assertFalse(adapter.sendText("""{"action":"rename","path":"/Sdcard"}"""))
            assertFalse(adapter.sendText("""{"action":"uploadcancel","reqid":7}"""))
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun p10StreamsRawBytesIncludingJsonPrefixWithoutCorruption() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles)
        val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var requested = ""
        val adapter = AndroidFilesConnection(raw, scope) { requested = it; data }
        try {
            adapter.connect()
            assertTrue(adapter.sendText("""{"action":"download","sub":"start","path":"/Images/test.jpg","id":9}"""))
            val bytes = ByteArray(65535) { (it % 256).toByte() }.apply { this[0] = 123 }
            data.input.send(RelayFrame.Text("""{"op":"ok","size":65535}"""))
            data.input.send(RelayFrame.Binary(bytes))
            data.input.close()
            val received = withTimeout(3000) { adapter.incoming.take(7).toList() }
            assertEquals("Images/test.jpg", requested)
            val start = JSONObject((received.first() as RelayFrame.Text).value)
            assertEquals("start", start.getString("sub")); assertEquals(9, start.getInt("id"))
            val packets = received.drop(1).map { (it as RelayFrame.Binary).value }
            assertTrue(packets.dropLast(1).none { AgentFilesProtocol.downloadFinal(it) })
            assertTrue(AgentFilesProtocol.downloadFinal(packets.last()))
            assertArrayEquals(bytes, packets.flatMap { it.drop(4) }.toByteArray())
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun emptyP10DownloadHasAnExplicitFinalFrame() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"/Sdcard/empty","id":1}""")
            data.input.send(RelayFrame.Text("""{"op":"ok","size":0}""")); data.input.close()
            val frames = withTimeout(2000) { adapter.incoming.take(2).toList() }
            assertArrayEquals(byteArrayOf(1, 0, 0, 1), (frames.last() as RelayFrame.Binary).value)
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun truncatedP10DownloadReportsFailure() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"/Sdcard/test","id":3}""")
            data.input.send(RelayFrame.Text("""{"op":"ok","size":50}"""))
            data.input.send(RelayFrame.Binary(byteArrayOf(1, 2, 3))); data.input.close()
            val frames = withTimeout(2000) { adapter.incoming.take(3).toList() }
            assertEquals("cancel", JSONObject((frames.last() as RelayFrame.Text).value).getString("sub"))
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun extraP10BytesNeverPublishSuccessfulEof() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"/Sdcard/test","id":4}""")
            data.input.send(RelayFrame.Text("""{"op":"ok","size":3}"""))
            data.input.send(RelayFrame.Binary(byteArrayOf(1, 2, 3)))
            data.input.send(RelayFrame.Binary(byteArrayOf(4))); data.input.close()
            val frames = withTimeout(2000) { adapter.incoming.take(3).toList() }
            assertFalse(AgentFilesProtocol.downloadFinal((frames[1] as RelayFrame.Binary).value))
            assertEquals("cancel", JSONObject((frames.last() as RelayFrame.Text).value).getString("sub"))
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun failedP10SocketAtExpectedSizeNeverPublishesEof() = runBlocking {
        val raw = Fake(RelayProtocol.AgentFiles); val data = Fake(RelayProtocol.AgentFileDownload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = AndroidFilesConnection(raw, scope) { data }
        try {
            adapter.connect()
            adapter.sendText("""{"action":"download","sub":"start","path":"/Sdcard/test","id":5}""")
            val result = async { withTimeout(2000) { adapter.incoming.take(3).toList() } }
            data.input.send(RelayFrame.Text("""{"op":"ok","size":3}"""))
            data.input.send(RelayFrame.Binary(byteArrayOf(1, 2, 3)))
            data.input.close(java.io.IOException("Synthetic transport loss"))
            val frames = result.await()
            assertFalse(AgentFilesProtocol.downloadFinal((frames[1] as RelayFrame.Binary).value))
            assertEquals("cancel", JSONObject((frames.last() as RelayFrame.Text).value).getString("sub"))
        } finally { adapter.close(); scope.cancel() }
    }
}
