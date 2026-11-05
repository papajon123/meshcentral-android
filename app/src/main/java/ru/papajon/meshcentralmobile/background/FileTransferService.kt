package ru.papajon.meshcentralmobile.background

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** Protects explicit transfers and their document-picker reservations; never restarted after death. */
class FileTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workspace get() = ConnectionWorkspace.get(application)
    private var monitoring: Job? = null
    private var completedNormally = false
    private var notifyCompletion = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.workspace_transfer_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancelTransfers(); finish(); return START_NOT_STICKY }
        val startMonitor = monitoring?.isActive != true
        if (startMonitor) notifyCompletion = false
        if (intent?.action != PICKER_RESERVATION) notifyCompletion = true
        completedNormally = false
        startForeground(ID, notification(false))
        if (startMonitor) monitoring = scope.launch {
            while (activeTransfers()) { delay(500) }
            // Selecting or cancelling a source is not a completed data transfer.
            if (notifyCompletion) getSystemService(NotificationManager::class.java).notify(COMPLETE_ID, notification(true))
            finish()
        }
        return START_NOT_STICKY
    }
    private fun activeTransfers(): Boolean = cleanups.get() > 0 || workspace.transfers.hasPending || workspace.sessions.any {
        it.files?.state?.value?.let { state -> state.uploadPickerPending || state.batchBusy || (state.busy && state.transferName != null) } == true
    }
    private fun cancelTransfers() {
        workspace.transfers.cancelAll()
        workspace.sessions.forEach { it.files?.let { files ->
            val state = files.state.value
            if (state.uploadPickerPending || state.batchBusy || state.transferName != null) files.cancel()
        } }
    }
    private fun notification(finished: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 701, Intent(this, MainActivity::class.java).setAction("show-transfers"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 702, Intent(this, FileTransferService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(if (finished) R.string.workspace_transfers_finished else R.string.workspace_transfers_active))
            .setContentText(getString(R.string.workspace_transfer_open_details))
            .setContentIntent(open).setOngoing(!finished).setAutoCancel(finished)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (!finished) addAction(0, getString(R.string.cancel), stop) }
            .build()
    }
    private fun finish() { completedNormally = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelTransfers(); finish() }
    override fun onDestroy() { if (!completedNormally) cancelTransfers(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PICKER_RESERVATION = "file-picker-reservation"
        private val cleanups = java.util.concurrent.atomic.AtomicInteger()
        fun beginCleanup() { cleanups.incrementAndGet() }
        fun endCleanup() { cleanups.decrementAndGet() }
        private const val CHANNEL = "file-transfers"; private const val ID = 701; private const val COMPLETE_ID = 702; private const val CANCEL = "cancel-file-transfers" }
}
