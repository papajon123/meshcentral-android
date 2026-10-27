package ru.papajon.meshcentralmobile.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.forward.PortForwardManager
import ru.papajon.meshcentralmobile.forward.ForwardNotificationEntry
import ru.papajon.meshcentralmobile.forward.forwardNotificationEntries
import ru.papajon.meshcentralmobile.forward.forwardNotifications
import ru.papajon.meshcentralmobile.forward.coalescedForwardNotifications
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** User-started communication with remote devices. No boot receiver, sticky restart or saved intent. */
class PortForwardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: PortForwardManager
    private var monitor: Job? = null
    private var finishedNormally = false
    private var foregroundStarted = false
    private var lastPublicationNanos = 0L
    private var displayed = emptyList<ForwardNotificationEntry>()
    override fun onCreate() {
        super.onCreate()
        manager = ConnectionWorkspace.get(application).client.portForwards
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pf_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ALL || intent == null) {
            monitor?.cancel(); monitor = null
            manager.stopAll(); finish(); return START_NOT_STICKY
        }
        val current = ConnectionWorkspace.get(application).client.portForwards
        // A repeated start must not restart the rate limiter or bypass it with notify(initial).
        if (manager === current && foregroundStarted && monitor?.isActive == true) return START_NOT_STICKY
        monitor?.cancel()
        monitor = null
        if (manager !== current) { manager.stopAll(); manager = current }
        finishedNormally = false
        val initial = forwardNotificationEntries(manager.states.value)
        try {
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(initial), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else startForeground(ID, notification(initial))
                foregroundStarted = true
                displayed = initial
                lastPublicationNanos = System.nanoTime()
            }
        } catch (_: Exception) { manager.serviceFailed(); finish(); return START_NOT_STICKY }
        val owner = manager
        monitor = scope.launch {
            try {
                owner.states.forwardNotifications().onStart {
                    val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastPublicationNanos)
                    if (elapsed < 1000) delay(1000 - elapsed)
                }.coalescedForwardNotifications()
                    .takeWhile { it.isNotEmpty() }.collect { entries -> publishIfChanged(entries) }
                finish()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { owner.serviceFailed(); finish() }
        }
        return START_NOT_STICKY
    }
    private fun publishIfChanged(entries: List<ForwardNotificationEntry>) {
        if (entries == displayed) return
        getSystemService(NotificationManager::class.java).notify(ID, notification(entries))
        displayed = entries
        lastPublicationNanos = System.nanoTime()
    }
    private fun notification(entries: List<ForwardNotificationEntry>): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, ID, Intent(this, PortForwardService::class.java).setAction(STOP_ALL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = entries.map { "${it.name}: 127.0.0.1:${it.port} · ${getString(R.string.pf_connections, it.connections)}" }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.pf_title)).setContentText(active.joinToString(" · "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> active.forEach { style.addLine(it) } })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.pf_stop_all), stop).build()
    }
    private fun finish() { finishedNormally = true; foregroundStarted = false; displayed = emptyList(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { if (!finishedNormally) manager.stopAll(); scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { manager.stopAll(); finish() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CHANNEL = "port-forwarding"
        const val ID = 711
        const val STOP_ALL = "stop-port-forwarding"
    }
}
