package ru.papajon.meshcentralmobile.transfer

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.papajon.meshcentralmobile.R

/** SAF-share input is bounded and never widened beyond content URIs with a read grant. */
class SharedContentReaderTest {
    private lateinit var context: android.content.Context
    private lateinit var reader: SharedContentReader
    private val files = mutableListOf<File>()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        reader = SharedContentReader(context)
    }

    @After fun cleanup() {
        files.forEach { it.delete() }
    }

    @Test fun sendMultipleReadsOnlyContentUrisWithBoundedMetadata() {
        val first = shareFile(byteArrayOf(1, 2, 3), "first.bin")
        val second = shareFile(byteArrayOf(4), "second.bin")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val result = reader.read(intent)
        assertEquals(2, result.size)
        assertEquals("first.bin", result[0].name)
        assertEquals(3L, result[0].size ?: -1L)
        assertEquals("second.bin", result[1].name)
        assertEquals(1L, result[1].size ?: -1L)
    }

    @Test fun clipDataIsAcceptedAndGrantIsRequired() {
        val uri = shareFile(byteArrayOf(9), "clip.bin")
        val granted = Intent(Intent.ACTION_SEND).apply {
            clipData = android.content.ClipData.newUri(context.contentResolver, "shared", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals("clip.bin", reader.read(granted).single().name)

        val noGrant = Intent(Intent.ACTION_SEND).apply {
            clipData = android.content.ClipData.newUri(context.contentResolver, "shared", uri)
        }
        assertTrue(reader.read(noGrant).isEmpty())
    }

    @Test fun fileAndHttpPathsAreNeverOpenedOrReturned() {
        val fileIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(context.cacheDir, "private.bin")))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertTrue(reader.read(fileIntent).isEmpty())
        val httpIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("https://example.invalid/file"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertTrue(reader.read(httpIntent).isEmpty())
    }

    @Test fun metadataErrorUsesLocalizedBlankNameFallbackPerItem() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            data = Uri.parse("content://missing.provider/file")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        val item = reader.read(intent).single()
        assertEquals(context.getString(R.string.transfer_shared_file_fallback, 1), item.name)
        assertNull(item.size)
    }

    @Test fun oversizedShareIsRejectedInsteadOfSilentlyDroppingFiles() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList((1..21).map { Uri.parse("content://missing.provider/$it") }),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val result = reader.read(intent)
        assertEquals(0, result.size)
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList((1..20).map { Uri.parse("content://missing.provider/$it") }))
        val accepted = reader.read(intent)
        assertEquals(20, accepted.size)
        assertEquals(context.getString(R.string.transfer_shared_file_fallback, 20), accepted[19].name)
    }

    private fun shareFile(bytes: ByteArray, name: String): Uri {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, name).apply { writeBytes(bytes) }
        files += file
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    }
}
