package ru.papajon.meshcentralmobile.integration

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** The launcher requests visible rows; the favorites list never fills a single Binder parcel. */
class FavoritesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var rows = emptyList<LauncherDevice>()
        override fun onCreate() { onDataSetChanged() }
        override fun onDataSetChanged() {
            // While hidden the factory owns no device metadata at all, so a stale collection
            // refresh cannot expose names or states even between widget updates.
            rows = if (LauncherPrivacy.hidden(applicationContext)) emptyList()
            else LauncherStore(applicationContext).read()?.devices.orEmpty().filter { it.favorite }
        }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount(): Int = rows.size
        override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.let { FavoritesWidget.row(applicationContext, it) }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
