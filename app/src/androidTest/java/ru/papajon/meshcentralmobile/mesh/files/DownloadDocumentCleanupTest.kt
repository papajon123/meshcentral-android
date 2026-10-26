package ru.papajon.meshcentralmobile.mesh.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real SAF branch through the debug-only synthetic DocumentsProvider.
 * The cleanup code is not mirrored here: every assertion goes through ContentResolver.
 */
class DownloadDocumentCleanupTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val cleanup get() = DownloadDocumentCleanup(context)

    @Before fun resetProvider() {
        FixtureDocumentsProvider.resetForTest(context)
    }

    @After fun clearProvider() {
        FixtureDocumentsProvider.resetForTest(context)
    }

    @Test fun deletesDocumentThatProviderAllowsToDelete() {
        val bytes = byteArrayOf(11, 22, 33)
        val document = newDocument("report.bin", bytes, allowDelete = true, allowRename = true)
        assertTrue("The fixture must use the real DocumentsContract branch",
            DocumentsContract.isDocumentUri(context, document))

        assertEquals(DownloadDocumentCleanup.Result.Deleted, cleanup.discard(document))

        assertNull(displayName(document))
        assertFalse(FixtureDocumentsProvider.backingFileExists(context,
            DocumentsContract.getDocumentId(document)))
    }

    @Test fun deniedDeleteRenamesDocumentToPartial() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val document = newDocument("report.bin", bytes, allowDelete = false, allowRename = true)

        assertEquals(DownloadDocumentCleanup.Result.Marked, cleanup.discard(document))

        assertEquals("report.bin.partial", displayName(document))
        assertArrayEquals(bytes, contents(document))
    }

    @Test fun deniedDeleteAndRenameRetainsOriginalDocument() {
        val bytes = byteArrayOf(5, 6, 7)
        val document = newDocument("report.bin", bytes, allowDelete = false, allowRename = false)

        assertEquals(DownloadDocumentCleanup.Result.Retained, cleanup.discard(document))

        assertEquals("report.bin", displayName(document))
        assertArrayEquals(bytes, contents(document))
    }

    @Test fun alreadyPartialDocumentStaysMarkedWithoutRename() {
        val bytes = byteArrayOf(8, 9)
        val document = newDocument("report.bin.partial", bytes, allowDelete = false, allowRename = false)

        assertEquals(DownloadDocumentCleanup.Result.Marked, cleanup.discard(document))

        assertEquals("report.bin.partial", displayName(document))
        assertArrayEquals(bytes, contents(document))
    }

    @Test fun nonContentUriIsIgnoredWithoutModifyingTheFile() {
        val bytes = byteArrayOf(42, 43)
        val file = File(context.cacheDir, "plain-cleanup-${System.nanoTime()}.bin").apply { writeBytes(bytes) }
        try {
            assertNull(cleanup.discard(Uri.fromFile(file)))

            assertTrue(file.isFile)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun newDocument(name: String, bytes: ByteArray, allowDelete: Boolean, allowRename: Boolean): Uri =
        FixtureDocumentsProvider.addForTest(context, name, bytes, allowDelete, allowRename)

    private fun displayName(document: Uri): String? = resolver.query(
        document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun contents(document: Uri): ByteArray =
        requireNotNull(resolver.openInputStream(document)).use { it.readBytes() }
}
