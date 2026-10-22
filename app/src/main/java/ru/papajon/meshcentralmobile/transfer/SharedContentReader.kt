package ru.papajon.meshcentralmobile.transfer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import ru.papajon.meshcentralmobile.R

/** One shared content URI plus bounded display metadata. The URI stays in memory only. */
data class SharedFile(
    val uri: Uri,
    val name: String,
    val size: Long? = null,
) {
    override fun toString(): String = "SharedFile(name=$name, size=$size)"
}

/**
 * Reads ACTION_SEND / ACTION_SEND_MULTIPLE without opening the shared source and without turning a
 * file/http/private path into a readable URI. It never saves a URI; a persistable grant is taken
 * only when the sender offered one, and failures are contained per item.
 */
class SharedContentReader(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val resources = context.applicationContext.resources

    @Suppress("DEPRECATION")
    fun read(intent: Intent): List<SharedFile> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        if ((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return emptyList()
        val persistable = (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        val candidates = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            if (clip.itemCount > MAX_FILES) return emptyList()
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { candidates += it }
            }
        }
        try {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    if (it.size > MAX_FILES) return emptyList()
                    candidates.addAll(it)
                }
            } else {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { candidates += it }
            }
        } catch (_: Exception) {
            // A malformed third-party extra must not break the remaining clip data.
        }
        intent.data?.let { candidates += it }
        if (candidates.size > MAX_FILES) return emptyList()

        return candidates.asSequence()
            .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
            .take(MAX_FILES)
            .mapIndexed { index, uri -> readOne(uri, index, persistable) }
            .toList()
    }

    private fun readOne(uri: Uri, index: Int, persistable: Boolean): SharedFile {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        name = cursor.getString(nameColumn)?.take(256)
                    }
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                    }
                }
            }
        } catch (_: Exception) {
            // Metadata is untrusted and optional; the upload itself will report an unreadable URI.
        }
        if (persistable) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Transient grant is still enough for this foreground, same-process upload.
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() }
            ?: resources.getString(R.string.transfer_shared_file_fallback, index + 1)
        return SharedFile(uri = uri, name = displayName, size = size)
    }

    private companion object {
        const val MAX_FILES = 20
    }
}
