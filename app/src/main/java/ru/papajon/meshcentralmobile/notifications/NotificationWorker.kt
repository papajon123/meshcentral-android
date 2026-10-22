package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** The only WorkManager input is the opaque profile id; the current profile supplies the URL. */
class NotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(NotificationWorkScheduler.KEY_PROFILE_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            ?: return Result.failure()
        val outcome = try {
            NotificationPoller(applicationContext, profileId).poll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationPollResult.RETRY
        }
        return when (outcome) {
            NotificationPollResult.RETRY ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
