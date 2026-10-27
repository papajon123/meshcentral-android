package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug-only synthetic DocumentsProvider for instrumentation tests.
 *
 * It never exposes a real path: every document is a fixture record whose bytes live in this
 * provider's own cache subdirectory. Tests configure a record entirely through the public
 * ContentResolver API, and each record decides whether delete and rename are allowed.
 */
class FixtureDocumentsProvider : DocumentsProvider() {
    private data class Fixture(
        val id: String,
        var displayName: String,
        val size: Int,
        val flags: Int,
        val allowDelete: Boolean,
        val allowRename: Boolean,
        val parent: String = ROOT_ID,
        val mime: String = MIME_TYPE,
    )

    private val lock = Any()
    private val fixtures = LinkedHashMap<String, Fixture>()
    private var nextId = 0

    override fun onCreate(): Boolean {
        fixturesDirectory().mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    Root.COLUMN_ROOT_ID -> ROOT_ID
                    Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                    Root.COLUMN_TITLE -> "Synthetic fixtures"
                    Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                    Root.COLUMN_AVAILABLE_BYTES -> 0L
                    else -> null
                }
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        synchronized(lock) {
            if (documentId == ROOT_ID) {
                documentCursor(projection, ROOT_ID, "Synthetic fixtures", Document.MIME_TYPE_DIR, 0L, Document.FLAG_DIR_SUPPORTS_CREATE)
            } else {
                val fixture = fixtures[documentId] ?: throw FileNotFoundException(documentId)
                documentCursor(projection, fixture.id, fixture.displayName, fixture.mime, File(fixturesDirectory(), fixture.id).length(),
                    fixture.flags)
            }
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        synchronized(lock) {
            if (parentDocumentId != ROOT_ID && fixtures[parentDocumentId]?.mime != Document.MIME_TYPE_DIR) throw FileNotFoundException(parentDocumentId)
            fixtures.values.filter { it.parent == parentDocumentId }.forEach { fixture ->
                cursor.addRow(columns.map { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> fixture.id
                        Document.COLUMN_DISPLAY_NAME -> fixture.displayName
                        Document.COLUMN_MIME_TYPE -> fixture.mime
                        Document.COLUMN_FLAGS -> fixture.flags
                        Document.COLUMN_SIZE -> File(fixturesDirectory(), fixture.id).length()
                        Document.COLUMN_LAST_MODIFIED -> 0L
                        else -> null
                    }
                })
            }
        }
        return cursor
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = synchronized(lock) {
        if (parentDocumentId != ROOT_ID && fixtures[parentDocumentId]?.mime != Document.MIME_TYPE_DIR) throw FileNotFoundException(parentDocumentId)
        val id = "fixture-${++nextId}"
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            (if (mimeType == Document.MIME_TYPE_DIR) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE)
        fixtures[id] = Fixture(id, displayName, 0, flags, true, true, parentDocumentId, mimeType)
        File(fixturesDirectory(), id).writeBytes(ByteArray(0))
        id
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = synchronized(lock) {
        var parent = fixtures[documentId]?.parent
        repeat(64) {
            if (parent == parentDocumentId) return@synchronized true
            parent = fixtures[parent]?.parent
        }
        false
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = synchronized(lock) {
            if (!fixtures.containsKey(documentId)) throw FileNotFoundException(documentId)
            File(fixturesDirectory(), documentId)
        }
        return ParcelFileDescriptor.open(file, fileMode(mode))
    }

    override fun deleteDocument(documentId: String) {
        synchronized(lock) {
            val fixture = fixtures[documentId] ?: throw FileNotFoundException(documentId)
            if (!fixture.allowDelete) throw FileNotFoundException("Deletion denied for $documentId")
            val file = File(fixturesDirectory(), documentId)
            if (file.exists() && !file.delete()) throw FileNotFoundException("Cannot delete $documentId")
            fixtures.remove(documentId)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String? = synchronized(lock) {
        val fixture = fixtures[documentId] ?: throw FileNotFoundException(documentId)
        if (!fixture.allowRename) throw FileNotFoundException("Rename denied for $documentId")
        fixture.displayName = displayName
        documentId
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method in setOf(METHOD_RESET, METHOD_ADD, METHOD_BACKING_FILE_EXISTS) &&
            android.os.Binder.getCallingUid() != android.os.Process.myUid()) {
            throw SecurityException("Fixture setup is restricted to this test application")
        }
        return when (method) {
        METHOD_RESET -> {
            resetFixtures()
            Bundle()
        }
        METHOD_ADD -> addFixture(extras)
        METHOD_BACKING_FILE_EXISTS -> Bundle().apply {
            val documentId = extras?.getString(KEY_DOCUMENT_ID)
            val exists = documentId != null && documentId.matches(Regex("fixture-[0-9]+")) &&
                synchronized(lock) { File(fixturesDirectory(), documentId).isFile }
            putBoolean(KEY_EXISTS, exists)
        }
        else -> super.call(method, arg, extras)
        }
    }

    private fun addFixture(input: Bundle?): Bundle = synchronized(lock) {
        val extras = input ?: throw IllegalArgumentException("Fixture extras are required")
        val name = extras.getString(KEY_NAME)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Fixture name is required")
        val bytes = extras.getByteArray(KEY_BYTES) ?: ByteArray(0)
        require(bytes.size <= 65_536 && name.length <= 256)
        val allowDelete = extras.getBoolean(KEY_ALLOW_DELETE)
        val allowRename = extras.getBoolean(KEY_ALLOW_RENAME)
        val id = "fixture-${++nextId}"
        File(fixturesDirectory(), id).writeBytes(bytes)
        var flags = 0
        if (allowDelete) flags = flags or Document.FLAG_SUPPORTS_DELETE
        if (allowRename) flags = flags or Document.FLAG_SUPPORTS_RENAME
        fixtures[id] = Fixture(id, name, bytes.size, flags, allowDelete, allowRename)
        Bundle().apply { putString(KEY_DOCUMENT_ID, id) }
    }

    private fun resetFixtures() {
        synchronized(lock) {
            fixtures.clear()
            nextId = 0
            fixturesDirectory().listFiles()?.forEach { it.delete() }
        }
    }

    private fun documentCursor(
        projection: Array<out String>?,
        id: String,
        displayName: String,
        mimeType: String,
        size: Long,
        flags: Int,
    ): Cursor {
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> id
                    Document.COLUMN_DISPLAY_NAME -> displayName
                    Document.COLUMN_MIME_TYPE -> mimeType
                    Document.COLUMN_FLAGS -> flags
                    Document.COLUMN_SIZE -> size
                    Document.COLUMN_LAST_MODIFIED -> 0L
                    else -> null
                }
            })
        }
    }

    private fun fixturesDirectory(): File =
        File(requireNotNull(context).cacheDir, DIRECTORY).apply { mkdirs() }

    private fun fileMode(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> throw IllegalArgumentException("Unsupported mode: $mode")
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".testdocuments"
        const val ROOT_ID = "fixture-root"

        private const val DIRECTORY = "testdocuments"
        private const val MIME_TYPE = "application/octet-stream"
        private const val METHOD_RESET = "fixtureReset"
        private const val METHOD_ADD = "fixtureAdd"
        private const val METHOD_BACKING_FILE_EXISTS = "fixtureBackingFileExists"
        private const val KEY_NAME = "name"
        private const val KEY_BYTES = "bytes"
        private const val KEY_ALLOW_DELETE = "allowDelete"
        private const val KEY_ALLOW_RENAME = "allowRename"
        private const val KEY_DOCUMENT_ID = "documentId"
        private const val KEY_EXISTS = "exists"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        private fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX
        private fun providerUri(context: Context): Uri = Uri.parse("content://" + authority(context))

        fun resetForTest(context: Context) {
            context.contentResolver.call(providerUri(context), METHOD_RESET, null, null)
        }

        fun addForTest(
            context: Context,
            name: String,
            bytes: ByteArray,
            allowDelete: Boolean,
            allowRename: Boolean,
        ): Uri {
            val extras = Bundle().apply {
                putString(KEY_NAME, name)
                putByteArray(KEY_BYTES, bytes)
                putBoolean(KEY_ALLOW_DELETE, allowDelete)
                putBoolean(KEY_ALLOW_RENAME, allowRename)
            }
            val result = requireNotNull(
                context.contentResolver.call(providerUri(context), METHOD_ADD, null, extras),
            ) { "Fixture provider did not create a document" }
            val id = requireNotNull(result.getString(KEY_DOCUMENT_ID)) {
                "Fixture provider returned no document id"
            }
            return DocumentsContract.buildDocumentUri(authority(context), id)
        }

        fun backingFileExists(context: Context, documentId: String): Boolean {
            val result = context.contentResolver.call(providerUri(context), METHOD_BACKING_FILE_EXISTS, null,
                Bundle().apply { putString(KEY_DOCUMENT_ID, documentId) })
            return result?.getBoolean(KEY_EXISTS) == true
        }
    }
}
