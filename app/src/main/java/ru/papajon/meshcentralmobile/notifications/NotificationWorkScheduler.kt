package ru.papajon.meshcentralmobile.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WorkManager owns the 15-minute best-effort cadence. Names are derived from a hash of the profile
 * id, and the worker payload is only that opaque id.
 */
object NotificationWorkScheduler {
    const val KEY_PROFILE_ID = "profileId"
    private const val PERIODIC_PREFIX = "mobilecompanion-notifications-periodic-"
    private const val ONE_TIME_PREFIX = "mobilecompanion-notifications-first-"
    private const val TAG_PREFIX = "mobilecompanion-notifications-"

    fun schedule(context: Context, profileId: String) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val hash = profileHash(profileId)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<NotificationWorker>(
            NotificationContract.POLL_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PREFIX + hash)
            .setInputData(androidx.work.workDataOf(KEY_PROFILE_ID to profileId))
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_PREFIX + hash,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        val firstCheck = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PREFIX + hash)
            .setInputData(androidx.work.workDataOf(KEY_PROFILE_ID to profileId))
            .build()
        workManager.enqueueUniqueWork(
            ONE_TIME_PREFIX + hash,
            ExistingWorkPolicy.REPLACE,
            firstCheck,
        )
    }

    fun cancel(context: Context, profileId: String) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val hash = profileHash(profileId)
        workManager.cancelUniqueWork(PERIODIC_PREFIX + hash)
        workManager.cancelUniqueWork(ONE_TIME_PREFIX + hash)
        workManager.cancelAllWorkByTag(TAG_PREFIX + hash)
    }

    private fun profileHash(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
