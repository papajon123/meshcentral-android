package ru.papajon.meshcentralmobile.companion

import ru.papajon.meshcentralmobile.json.optText

import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import ru.papajon.meshcentralmobile.mesh.MeshCentralClient

/**
 * Typed client for the optional `mobilecompanion` MeshCentral plugin.
 *
 * [request] receives a complete plugin command and returns the matching plugin reply. It is
 * intentionally the only transport seam: [MeshCentralClient.requestControl] correlates replies
 * by the broker-generated `requestId`; this class never opens or writes a WebSocket itself.
 */
class CompanionClient(
    private val request: suspend (JSONObject) -> JSONObject,
) {
    constructor(
        meshClient: MeshCentralClient,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ) : this(
        request = { command ->
            meshClient.requestControl(
                command = command,
                responseAction = RESPONSE_ACTION,
                nodeId = null,
                timeoutMillis = timeoutMillis,
            )
        },
    )

    suspend fun capabilities(): CompanionCapabilities =
        CompanionParser.parseCapabilities(execute(OP_CAPABILITIES, JSONObject()))

    suspend fun startJob(
        nodeId: String,
        template: CompanionTemplate,
        parameters: Map<String, String>,
        operationId: String,
        createdAt: Long,
        confirmed: Boolean,
    ): CompanionJob {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        require(operationId.isNotBlank()) { "operationId is required" }
        val fields = JSONObject()
            .put("nodeid", nodeId)
            .put("templateId", template.id)
            .put("parameters", JSONObject(parameters))
            .put("operationId", operationId)
            .put("createdAt", createdAt)
            .put("confirmed", confirmed)
        val data = execute(OP_START_JOB, fields)
        val job = CompanionParser.parseJob(data.optJSONObject("job"))
        if (job.nodeId != nodeId) throw CompanionException(CompanionErrorCode.PROTOCOL)
        return job
    }

    suspend fun getJob(jobId: String): CompanionJob {
        require(jobId.isNotBlank()) { "jobId is required" }
        val data = execute(OP_GET_JOB, JSONObject().put("jobId", jobId))
        return CompanionParser.parseJob(data.optJSONObject("job"))
    }

    suspend fun listJobs(nodeId: String, limit: Int = DEFAULT_LIST_LIMIT): List<CompanionJob> {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        val safeLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val data = execute(
            OP_LIST_JOBS,
            JSONObject().put("nodeid", nodeId).put("limit", safeLimit),
        )
        return CompanionParser.parseJobs(data).filter { it.nodeId == nodeId }
    }

    suspend fun cancelJob(jobId: String): CompanionJob {
        require(jobId.isNotBlank()) { "jobId is required" }
        val data = execute(OP_CANCEL_JOB, JSONObject().put("jobId", jobId))
        return CompanionParser.parseJob(data.optJSONObject("job"))
    }

    suspend fun events(since: Long = 0L, limit: Int = DEFAULT_EVENT_LIMIT): CompanionEventsPage {
        val safeSince = since.coerceAtLeast(0L)
        val safeLimit = limit.coerceIn(1, MAX_EVENT_LIMIT)
        val data = execute(
            OP_EVENTS,
            JSONObject().put("since", safeSince).put("limit", safeLimit),
        )
        return CompanionParser.parseEvents(data)
    }

    /**
     * `deleteJob` is not part of protocol v1. A v1 server answers `UNKNOWN_OP`, which surfaces
     * as [CompanionErrorCode.UNKNOWN_OP]. The controller then removes nothing locally.
     */
    suspend fun deleteJob(jobId: String): CompanionJob? {
        require(jobId.isNotBlank()) { "jobId is required" }
        val data = execute(OP_DELETE_JOB, JSONObject().put("jobId", jobId))
        val job = data.optJSONObject("job")
        return if (job == null) null else CompanionParser.parseJob(job)
    }

    private suspend fun execute(op: String, fields: JSONObject): JSONObject {
        val command = JSONObject()
            .put("action", ACTION_PLUGIN)
            .put("plugin", COMPANION_PLUGIN_ID)
            .put("op", op)
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in RESERVED_COMMAND_KEYS) command.put(key, fields.opt(key))
        }
        val response = try {
            request(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (response.optText("action") != ACTION_PLUGIN ||
            response.optText("plugin") != COMPANION_PLUGIN_ID
        ) {
            throw CompanionException(CompanionErrorCode.PROTOCOL)
        }
        if (!response.optBoolean("ok", false)) {
            val errorObject = response.optJSONObject("error")
            if (errorObject == null) throw CompanionException(CompanionErrorCode.PROTOCOL)
            val code = errorObject.optText("code").takeIf { it.isNotBlank() }
            // The server `message` is intentionally ignored.
            throw CompanionException(CompanionErrorCode.fromWire(code))
        }
        return response.optJSONObject("data")
            ?: throw CompanionException(CompanionErrorCode.PROTOCOL)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 40
        const val DEFAULT_EVENT_LIMIT = 50
        const val MAX_EVENT_LIMIT = 100

        private const val ACTION_PLUGIN = "plugin"
        private const val RESPONSE_ACTION = "plugin"
        private const val OP_CAPABILITIES = "capabilities"
        private const val OP_START_JOB = "startJob"
        private const val OP_GET_JOB = "getJob"
        private const val OP_LIST_JOBS = "listJobs"
        private const val OP_CANCEL_JOB = "cancelJob"
        private const val OP_EVENTS = "events"
        private const val OP_DELETE_JOB = "deleteJob"

        private val RESERVED_COMMAND_KEYS = setOf(
            "action",
            "plugin",
            "op",
            "requestId",
            "tag",
            "responseid",
            "userid",
        )

        /** Factory for callers that already own a connected [MeshCentralClient]. */
        fun from(
            meshClient: MeshCentralClient,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ): CompanionClient = CompanionClient(meshClient, timeoutMillis)

        /** Generates one operation id for a user-visible attempt; the caller keeps it. */
        fun newOperationId(): String = UUID.randomUUID().toString()
    }
}
