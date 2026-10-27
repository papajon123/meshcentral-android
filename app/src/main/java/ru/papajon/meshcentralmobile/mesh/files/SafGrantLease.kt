package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process

/** Reference-counted queue leases. Never releases a permission that predated this process lease. */
internal class SafGrantLease private constructor(private val release: () -> Unit) : AutoCloseable {
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; release() } }

    companion object {
        private data class Record(var users: Int, var acquired: Int)
        private val records = mutableMapOf<Uri, Record>()
        @Synchronized fun acquire(context: Context, uri: Uri, write: Boolean): SafGrantLease {
            require(uri.scheme == "content")
            val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
            // App-owned providers do not rely on an Activity's temporary URI grant.
            if (provider?.applicationInfo?.uid == Process.myUid()) return SafGrantLease {}
            val resolver = context.contentResolver
            val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            records[uri]?.let { record ->
                val granted = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                val current = (if (granted?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (granted?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                val added = required and current.inv()
                if (added != 0) resolver.takePersistableUriPermission(uri, added)
                record.acquired = record.acquired or added
                record.users++
                return SafGrantLease { release(context, uri) }
            }
            val old = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            val existing = (if (old?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (old?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            val added = required and existing.inv()
            if (added != 0) resolver.takePersistableUriPermission(uri, added)
            records[uri] = Record(1, added)
            return SafGrantLease { release(context, uri) }
        }
        @Synchronized private fun release(context: Context, uri: Uri) {
            val record = records[uri] ?: return
            if (--record.users > 0) return
            records.remove(uri)
            if (record.acquired != 0) runCatching { context.contentResolver.releasePersistableUriPermission(uri, record.acquired) }
        }
    }
}
