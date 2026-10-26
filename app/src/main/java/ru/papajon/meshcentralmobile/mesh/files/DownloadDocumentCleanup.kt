package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** Only for a newly created destination owned by a download, never for upload sources. */
internal class DownloadDocumentCleanup(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    enum class Result { Deleted, Marked, Retained }

    fun discard(uri: Uri): Result? {
        // ACTION_CREATE_DOCUMENT returns content URIs. Non-SAF callers retain their own ownership.
        if (uri.scheme != "content") return null
        val document = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        val deleted = runCatching {
            if (document) DocumentsContract.deleteDocument(resolver, uri)
            else resolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
        if (deleted) return Result.Deleted
        val marked = runCatching {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return@runCatching false
            name.endsWith(".partial") || (document &&
                DocumentsContract.renameDocument(resolver, uri, "$name.partial") != null)
        }.getOrDefault(false)
        return if (marked) Result.Marked else Result.Retained
    }
}
