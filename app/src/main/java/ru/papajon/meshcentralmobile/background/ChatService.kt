package ru.papajon.meshcentralmobile.background

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Only explicit foreground chat actions start this service. Process death never reopens a chat. */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workspace get() = ConnectionWorkspace.get(application)
    private var monitoring: Job? = null
    private var finished = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.chat_service_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.chat_service_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        finished = false
        val pending = PendingIntent.getActivity(this, ID,
            Intent(this, MainActivity::class.java).setAction(SHOW_CHATS)
                .putExtra(ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.EXTRA_DELIVERY, android.os.SystemClock.elapsedRealtimeNanos())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.chat_service_active))
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        ServiceCompat.startForeground(this, ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        if (monitoring?.isActive != true) monitoring = scope.launch {
            workspace.activeChats.first { !it }
            finished = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!finished) workspace.closeChats()
        scope.cancel()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { workspace.closeChats(); finished = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val SHOW_CHATS = "show-chats"
        const val CHANNEL = "active-chats"
        const val ID = 880
    }
}
