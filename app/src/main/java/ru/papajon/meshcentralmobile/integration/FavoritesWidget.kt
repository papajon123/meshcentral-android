package ru.papajon.meshcentralmobile.integration

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import java.text.DateFormat
import java.util.Date

class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = renderAsync(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = renderAsync(context)
    private fun renderAsync(context: Context) {
        val pending = goAsync()
        LauncherCoordinator.render(context.applicationContext, force = true).invokeOnCompletion { pending?.finish() }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH) WorkManager.getInstance(context).enqueueUniqueWork("favorites-widget-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
    }

    companion object {
        private const val REFRESH = "ru.papajon.meshcentralmobile.WIDGET_REFRESH"

        fun updateAll(context: Context) {
            // Device configurations without an AppWidget service return null here.
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, views(context, it)) }
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_rows)
        }

        internal fun views(context: Context, id: Int, snapshot: LauncherSnapshot? = LauncherStore(context).read(),
            hidden: Boolean = LauncherPrivacy.hidden(context)): RemoteViews {
            if (hidden) return hiddenViews(context, id)
            val entries = snapshot?.devices.orEmpty().filter { it.favorite }
            return RemoteViews(context.packageName, R.layout.favorites_widget).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.integration_favorites))
                setTextViewText(R.id.widget_updated, snapshot?.takeIf { entries.isNotEmpty() }?.let {
                    context.getString(R.string.integration_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.updatedAt)))
                } ?: context.getString(R.string.integration_widget_empty))
                val needsApp = context.getSharedPreferences("widget_status", Context.MODE_PRIVATE).getBoolean("needs_app", false)
                setTextViewText(R.id.widget_hint, context.getString(if (needsApp) R.string.integration_open_to_refresh else R.string.integration_last_known))
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_rows, Intent(context, FavoritesWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("mesh-widget://favorites/$id")))
                // Collection fill-in data targets only MainActivity. Its token parser and lock
                // recheck the current account/device before any navigation.
                setPendingIntentTemplate(R.id.widget_rows, PendingIntent.getActivity(context, id,
                    Intent(context, MainActivity::class.java).setAction(DeviceShortcuts.ACTION_OPEN)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                setTextViewText(R.id.widget_refresh, context.getString(R.string.integration_refresh))
                setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
                setTextViewText(R.id.widget_open, context.getString(R.string.integration_open_app))
                setOnClickPendingIntent(R.id.widget_open, openPendingIntent(context))
            }
        }

        /** Locked/backgrounded surfaces contain no device names, states or update time. */
        private fun hiddenViews(context: Context, id: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.favorites_widget).apply {
                setTextViewText(R.id.widget_title, context.getString(R.string.integration_favorites))
                setTextViewText(R.id.widget_updated, context.getString(R.string.integration_private_hidden))
                setTextViewText(R.id.widget_hint, "")
                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_rows, Intent(context, FavoritesWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("mesh-widget://favorites/$id")))
                setPendingIntentTemplate(R.id.widget_rows, PendingIntent.getActivity(context, id,
                    Intent(context, MainActivity::class.java).setAction(DeviceShortcuts.ACTION_OPEN)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                setTextViewText(R.id.widget_refresh, context.getString(R.string.integration_refresh))
                setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
                setTextViewText(R.id.widget_open, context.getString(R.string.integration_open_app))
                setOnClickPendingIntent(R.id.widget_open, openPendingIntent(context))
            }

        private fun refreshPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, FavoritesWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun openPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        internal fun row(context: Context, device: LauncherDevice, hidden: Boolean = LauncherPrivacy.hidden(context)): RemoteViews {
            if (hidden) {
                return RemoteViews(context.packageName, R.layout.favorites_widget_row).apply {
                    setTextViewText(R.id.widget_device_name, context.getString(R.string.integration_private_device))
                    setTextViewText(R.id.widget_device_status, "")
                }
            }
            return RemoteViews(context.packageName, R.layout.favorites_widget_row).apply {
                setTextViewText(R.id.widget_device_name, device.name)
                setTextViewText(R.id.widget_device_status, context.getString(if (device.online) R.string.common_status_online else R.string.common_status_offline))
                setOnClickFillInIntent(R.id.widget_device_row, Intent().setData(DeviceShortcuts.intent(context, device.token).data))
            }
        }
    }
}
