package ru.papajon.meshcentralmobile.management

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.companion.*
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

/** One explicit start followed only by reads; uncertain mutations are never replayed. */
internal class InspectorJobRunner(
    private val client: CompanionClient,
    private val nodeId: String,
    private val pollIntervalMillis: Long = 2_000,
    private val timeoutMillis: Long = 310_000,
) {
    suspend fun run(
        template: CompanionTemplate,
        parameters: Map<String, String> = emptyMap(),
        confirmed: Boolean = false,
    ): CompanionResult {
        var job = client.startJob(
            nodeId, template, parameters, UUID.randomUUID().toString(), System.currentTimeMillis(), confirmed,
        )
        val jobId = job.id
        validate(job, jobId, template)
        withTimeout(timeoutMillis) {
            while (job.active) {
                delay(pollIntervalMillis)
                job = client.getJob(jobId)
                validate(job, jobId, template)
            }
        }
        if (job.verifiedComplete) return job.result!!
        val message = job.errorCode?.messageResourceId() ?: when {
            job.status == CompanionJobStatus.CANCELLED -> R.string.companion_status_cancelled
            job.resultParseFailed -> R.string.companion_job_error_invalid_result
            job.exitCode != null && job.exitCode != 0 -> R.string.companion_job_error_command_failed
            job.status == CompanionJobStatus.COMPLETED -> R.string.companion_job_error_invalid_result
            else -> R.string.companion_job_error_unknown
        }
        throw InspectorJobException(UiText.resource(message))
    }

    private fun validate(job: CompanionJob, jobId: String, template: CompanionTemplate) {
        if (job.id != jobId || job.nodeId != nodeId || job.templateId != template.id) {
            throw CompanionException(CompanionErrorCode.PROTOCOL)
        }
    }
}

internal class InspectorJobException(val text: UiText) : Exception()

/** Never renders exception messages or arbitrary text from the server. */
internal fun inspectorFailureText(error: Exception): UiText {
    if (error is InspectorJobException) return error.text
    val code = when (error) {
        is CompanionException -> error.code
        is TimeoutCancellationException -> CompanionErrorCode.TIMEOUT
        is CancellationException -> throw error
        is ControlRequestException -> CompanionErrorCode.fromWire(error.code).let {
            if (it == CompanionErrorCode.UNKNOWN) CompanionErrorCode.NETWORK else it
        }
        is JSONException -> CompanionErrorCode.PROTOCOL
        is IOException -> CompanionErrorCode.NETWORK
        is IllegalArgumentException -> CompanionErrorCode.INVALID_ARGUMENT
        else -> return UiText.resource(R.string.workspace_management_failed)
    }
    return UiText.resource(code.messageResourceId())
}
