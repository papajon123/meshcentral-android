package ru.papajon.meshcentralmobile.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream

/** SAF streams only: no broad storage permissions, persistent grants or plaintext temp files. */
object BackupDocuments {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    fun read(resolver: ContentResolver, uri: Uri): ByteArray {
        require(uri.scheme == "content")
        return requireNotNull(resolver.openInputStream(uri)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > MAX_FILE_BYTES) throw BackupException(BackupFailureReason.Limit)
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
    }
    fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        require(uri.scheme == "content" && bytes.size <= MAX_FILE_BYTES)
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
    }
}
