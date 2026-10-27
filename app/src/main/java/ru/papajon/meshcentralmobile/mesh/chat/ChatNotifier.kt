package ru.papajon.meshcentralmobile.mesh.chat

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.notifications.NotificationContract
import ru.papajon.meshcentralmobile.notifications.NotificationNotifier

/** Reuses the application's private channel and permission policy, never its persistent event feed. */
object ChatNotifier {
    const val EXTRA_SESSION = "chat_session"
    const val EXTRA_DELIVERY = "chat_delivery"
    private const val ID = 42
    private fun tag(profile: String, session: String) = "chat:$profile:$session"

    @SuppressLint("MissingPermission")
    fun post(context: Context, profile: String, nodeId: String, session: String, unreadCount: Int = 1): Boolean {
        NotificationNotifier.ensureChannel(context)
        if (!NotificationNotifier.hasPermission(context)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("meshcentral-chat").authority("session")
                .appendPath(profile).appendPath(session).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationContract.EXTRA_PROFILE_ID, profile)
            putExtra(NotificationContract.EXTRA_NODE_ID, nodeId)
            putExtra(EXTRA_SESSION, session)
            putExtra(EXTRA_DELIVERY, android.os.SystemClock.elapsedRealtimeNanos())
        }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, NotificationContract.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_public_text)).build()
        val notification = NotificationCompat.Builder(context, NotificationContract.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.chat_unread, unreadCount))
            .setNumber(unreadCount).setOnlyAlertOnce(true)
            .setContentIntent(pending).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        return try {
            NotificationManagerCompat.from(context).notify(tag(profile, session), ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, profile: String, session: String) {
        NotificationManagerCompat.from(context).cancel(tag(profile, session), ID)
    }
}
