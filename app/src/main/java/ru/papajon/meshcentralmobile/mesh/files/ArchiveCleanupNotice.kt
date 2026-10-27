package ru.papajon.meshcentralmobile.mesh.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import ru.papajon.meshcentralmobile.R

internal object ArchiveCleanupNotice {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val pending: kotlinx.coroutines.flow.StateFlow<List<String>> = mutable
    @Synchronized fun dismiss(path: String) { mutable.value = mutable.value - path }
    fun show(context: Context, path: String) {
        synchronized(this) { if (path !in mutable.value) mutable.value = mutable.value + path }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("file-cleanup", context.getString(R.string.review_cleanup_title), NotificationManager.IMPORTANCE_DEFAULT))
        val message = context.getString(R.string.review_archive_left, RemoteFile(path, 3).displayName)
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { manager.notify(path.hashCode(), NotificationCompat.Builder(context, "file-cleanup")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.review_cleanup_title))
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build()) }
    }
}
