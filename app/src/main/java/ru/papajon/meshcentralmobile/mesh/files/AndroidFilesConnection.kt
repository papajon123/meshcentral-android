package ru.papajon.meshcentralmobile.mesh.files

import ru.papajon.meshcentralmobile.json.optText

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.session.*

/**
 * Official Android main MeshTunnel.kt:187-260,407-540,712-826. Adapts binary p5 JSON and
 * separate p10 downloads to FilesController's existing verified-transfer protocol.
 * No buffering of whole files; one bounded channel, no retries or replay.
 */
internal class AndroidFilesConnection(
    private val control: RelayConnection,
    scope: CoroutineScope,
    private val downloadFactory: (String) -> RelayConnection,
) : RelayConnection {
    override val protocol = RelayProtocol.AgentFiles
    override val androidFiles = true
    override val state = control.state
    private val frames = Channel<RelayFrame>(8)
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO +
        CoroutineExceptionHandler { _, _ ->
            // No exception or URL from a transport may reach the process exception handler.
            frames.close(java.io.IOException("Phone file connection ended"))
            control.close()
            download?.close()
        })
    override val incoming = frames.receiveAsFlow()
    private var reader: Job? = null
    private var download: RelayConnection? = null
    private var downloadJob: Job? = null

    init {
        worker.coroutineContext[Job]?.invokeOnCompletion {
            control.close(); download?.close(); frames.cancel()
        }
    }

    override fun connect() {
        if (reader != null) return
        reader = worker.launch {
            try {
                control.incoming.collect { frame ->
                    if (frame.isKeepalive()) return@collect
                    val text = when (frame) {
                        is RelayFrame.Text -> frame.value
                        is RelayFrame.Binary -> frame.value.toString(Charsets.UTF_8)
                    }
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json?.has("dir") == true && json.has("path") && !json.isNull("path")) {
                        // Keep the relative categories understood by AgentFilesProtocol;
                        // adding a synthetic slash would restore the pre-live-fix path model.
                        json.put("path", json.optText("path").trimStart('/'))
                        frames.send(RelayFrame.Text(json.toString()))
                    } else frames.send(frame)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                frames.close(java.io.IOException("Phone file connection ended"))
            } catch (_: Exception) {
                frames.close(java.io.IOException("Phone file connection ended"))
            } finally {
                downloadJob?.cancel()
                download?.close()
                frames.close()
            }
        }
        control.connect()
    }

    override fun sendText(value: String): Boolean {
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return false
        if (json.has("path") && json.isNull("path")) return false
        when (json.optText("action")) {
            "mkdir", "rename", "uploadcancel" -> return false // Not implemented by the official agent.
            "upload" -> if (json.optText("path").trim('/').isEmpty()) return false
            "download" -> return when (json.optText("sub")) {
                "start" -> startDownload(json.getInt("id"), json.getString("path"),
                    if (json.has("size")) json.getLong("size").takeIf { it >= 0 } else null)
                "startack", "ack" -> true // p10 streams bytes without application-level acknowledgements.
                "stop" -> { download?.close(); downloadJob?.cancel(); true }
                else -> false
            }
        }
        if (json.has("path")) json.put("path", json.getString("path").trimStart('/'))
        return control.sendBinary(json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun startDownload(id: Int, path: String, expectedSize: Long?): Boolean {
        if (state.value.phase != RelayPhase.Connected || !worker.isActive) return false
        download?.close()
        downloadJob?.cancel()
        val relay = downloadFactory(path.trimStart('/'))
        download = relay
        downloadJob = worker.launch {
            var size: Long? = null
            var received = 0L
            var endedNormally = false
            suspend fun begin(length: Long) {
                if (length < 0 || (expectedSize != null && expectedSize != length)) throw InvalidDownloadData("size")
                size = length
                frames.send(RelayFrame.Text(JSONObject().put("action", "download")
                    .put("sub", "start").put("id", id).toString()))
            }
            try {
                // Subscribe before starting: small/empty files may arrive immediately.
                coroutineScope {
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        relay.incoming.collect { frame ->
                            if (frame.isKeepalive()) return@collect
                            when (frame) {
                                is RelayFrame.Text -> {
                                    if (frame.value == "pong" || frame.value == "ping") return@collect
                                    val json = runCatching { JSONObject(frame.value) }.getOrElse { throw InvalidDownloadData("text") }
                                    if (json.optText("ctrlChannel") == "102938") return@collect
                                    if (size != null || json.optText("op") != "ok") throw InvalidDownloadData("header")
                                    val rawSize = json.opt("size") as? Number ?: throw InvalidDownloadData("size")
                                    val length = rawSize.toLong()
                                    if (length < 0 || rawSize.toString() != length.toString()) throw InvalidDownloadData("size")
                                    begin(length)
                                }
                                is RelayFrame.Binary -> {
                                    // Released 1.0.23 starts with bytes, main starts with op:ok.
                                    // Never interpret a JSON-looking binary file as a header.
                                    if (size == null) begin(expectedSize ?: throw InvalidDownloadData("size"))
                                    val length = requireNotNull(size)
                                    if (frame.value.size.toLong() > length - received) throw InvalidDownloadData("excess")
                                    var offset = 0
                                    while (offset < frame.value.size) {
                                        val count = minOf(AgentFilesProtocol.CHUNK_SIZE, frame.value.size - offset)
                                        received += count
                                        val packet = ByteArray(count + 4)
                                        packet[0] = 1
                                        frame.value.copyInto(packet, 4, offset, offset + count)
                                        frames.send(RelayFrame.Binary(packet))
                                        offset += count
                                    }
                                }
                            }
                        }
                        endedNormally = true
                    }
                    relay.connect()
                    collector.join()
                }
                // The released agent sends no frame at all for an empty file.
                if (endedNormally && size == null && expectedSize == 0L) begin(0)
                // Anything short of the declared size is a transport failure, however the close was reported.
                if (!endedNormally || size == null || received != size) throw java.io.EOFException("Download ended early")
                // Publish EOF only after a normal close: excess bytes or a failed socket must
                // never race with FilesController's success/readback path.
                frames.send(RelayFrame.Binary(byteArrayOf(1, 0, 0, 1)))
            } catch (cancelled: CancellationException) {
                // Explicit cancel has already ended the FilesController operation.
                if (!currentCoroutineContext().isActive) throw cancelled
                sendDownloadFailure(id, transportFailed = true)
            } catch (failure: Exception) {
                // Only our own protocol checks classify data as invalid; socket, relay and early-end failures are transport.
                sendDownloadFailure(id, transportFailed = failure !is InvalidDownloadData)
            } finally { relay.close() }
        }
        // A cancelled owner may prevent the launch body (and its finally) from running at all.
        downloadJob?.invokeOnCompletion { relay.close() }
        return true
    }

    /** A protocol violation inside the p10 stream; every other download failure is a transport failure. */
    private class InvalidDownloadData(reason: String) : Exception(reason)

    private fun sendDownloadFailure(id: Int, transportFailed: Boolean) {
        // A p5 close can race any p10 send. Never suspend/throw from catch or finally.
        if (frames.trySend(RelayFrame.Text(JSONObject().put("action", "download").put("sub", "cancel")
                .put("id", id).put("error", if (transportFailed) "transport" else "invalid-data").toString())).isFailure) {
            frames.close(java.io.IOException("Phone download failed"))
        }
    }

    private fun RelayFrame.isKeepalive() = this is RelayFrame.Binary && value.size == 1 && value[0] == 0.toByte()

    override fun sendBinary(value: ByteArray) = control.sendBinary(value)
    override fun close() { worker.cancel(); control.close(); download?.close(); frames.cancel() }
}
