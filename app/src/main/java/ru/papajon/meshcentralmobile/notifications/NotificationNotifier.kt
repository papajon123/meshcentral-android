package ru.papajon.meshcentralmobile.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R

/**
 * Posts generic, private notifications. The body never contains a hostname, address, account,
 * command result or diagnostic output, and the public version contains no event details at all.
 */
object NotificationNotifier {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NotificationContract.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NotificationContract.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            val channel = manager.getNotificationChannel(NotificationContract.NOTIFICATION_CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Returns true only after the platform accepted the notification. */
    @SuppressLint("MissingPermission")
    fun notifyEvent(context: Context, profileId: String, item: NotificationFeedItem): Boolean {
        ensureChannel(context)
        if (!hasPermission(context)) return false
        val notificationId = NotificationEventPolicy.stableNotificationId("$profileId|${item.id}")
        val pendingIntent = contentIntent(context, profileId, item, notificationId)
        val publicVersion = NotificationCompat.Builder(context, NotificationContract.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_push_title))
            .setContentText(context.getString(R.string.notification_public_text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val body = bodyFor(context, item.kind)
        val notification = NotificationCompat.Builder(context, NotificationContract.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_push_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(item.time)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify("$profileId|${item.id}", notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun contentIntent(
        context: Context,
        profileId: String,
        item: NotificationFeedItem,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("meshcentral-event").authority("event")
                .appendPath(profileId).appendPath(item.id).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationContract.EXTRA_PROFILE_ID, profileId)
            item.nodeId?.let { putExtra(NotificationContract.EXTRA_NODE_ID, it) }
            item.jobId?.let { putExtra(NotificationContract.EXTRA_JOB_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun bodyFor(context: Context, kind: NotificationEventKind): String = context.getString(
        when (kind) {
            NotificationEventKind.DEVICE_ONLINE -> R.string.notification_body_device_online
            NotificationEventKind.DEVICE_OFFLINE -> R.string.notification_body_device_offline
            NotificationEventKind.DEVICE_REQUEST_HELP -> R.string.notification_body_request_help
            NotificationEventKind.JOB_COMPLETED -> R.string.notification_body_job_completed
            NotificationEventKind.JOB_FAILED -> R.string.notification_body_job_failed
            NotificationEventKind.DEVICE_ALERT -> R.string.notification_body_device_alert
        },
    )
}
