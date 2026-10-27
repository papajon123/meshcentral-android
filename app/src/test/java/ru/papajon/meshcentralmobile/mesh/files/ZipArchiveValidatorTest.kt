package ru.papajon.meshcentralmobile.mesh.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val EOCD_SIGNATURE = 0x06054B50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
private const val ZIP64_EOCD_SIGNATURE = 0x06064B50L
private const val CENTRAL_SIGNATURE = 0x02014B50L
private const val LOCAL_SIGNATURE = 0x04034B50L

class ZipArchiveValidatorTest {
    @Test fun emptyJdkArchiveIsValid() {
        val archive = zipBytes()
        assertEquals(22, archive.size)
        assertTrue(validate(archive))
    }

    @Test fun jdkArchiveWithFilesAndDirectoriesIsValid() {
        val archive = zipBytes(
            "hello.txt" to "hello zip".toByteArray(),
            "dir/" to ByteArray(0),
            "dir/данные.txt" to "повтор ".repeat(100).toByteArray(),
        )
        assertTrue(validate(archive))
    }

    @Test fun properArchiveCommentIsAllowed() {
        val base = zipBytes("a.txt" to "a".toByteArray())
        val archive = appendComment(base, "release notes".toByteArray())
        assertTrue(validate(archive))
    }

    @Test fun fakeEocdSignatureInsideCommentDoesNotHideRealEocd() {
        val base = zipBytes("a.txt" to "a".toByteArray())
        val comment = ByteArray(30)
        // A complete-looking EOCD inside the comment, but with a multi-disk field.
        put32(comment, 0, EOCD_SIGNATURE)
        put16(comment, 4, 1)
        put16(comment, 20, comment.size - 22)
        val archive = appendComment(base, comment)
        assertTrue(validate(archive))
    }

    @Test fun truncatedAndArbitraryArchivesAreRejected() {
        val base = zipBytes("a.txt" to "a".toByteArray())
        assertFalse(validate(base.copyOf(base.size - 1)))
        assertFalse(validate(base.copyOf(base.size / 2)))
        assertFalse(validate(ByteArray(22)))
        assertFalse(validate(ByteArray(4096) { (it * 31 + 7).toByte() }))

        val trailingGarbage = base.copyOf(base.size + 1)
        trailingGarbage[base.size] = 0x42
        assertFalse(validate(trailingGarbage))
    }

    @Test fun damagedCentralAndLocalRecordsAreRejected() {
        val base = zipBytes("a.txt" to "hello".toByteArray())
        val eocd = eocdOffset(base)
        val central = read32(base, eocd + 16).toInt()
        val local = read32(base, central + 42).toInt()

        assertFalse(validate(copyAnd(base) { it[central] = 0 }))
        assertFalse(validate(copyAnd(base) { it[local] = 0 }))
        assertFalse(validate(copyAnd(base) { put32(it, central + 42, 0xFFFFFFF0L) }))
        assertFalse(validate(copyAnd(base) { put32(it, central + 42, central.toLong()) }))
        assertFalse(validate(copyAnd(base) { put16(it, central + 34, 1) }))
        assertFalse(validate(copyAnd(base) { put16(it, central + 34, 0xFFFF) }))
        assertFalse(validate(copyAnd(base) { put32(it, central + 20, 0xFFFFFFFFL) }))
        assertFalse(validate(copyAnd(base) { put16(it, local + 6, 0) }))
        assertFalse(validate(copyAnd(base) { it[local + 30] = 'b'.code.toByte() }))
        assertFalse(validate(copyAnd(base) { put16(it, local + 26, 0xFFFF) }))
        assertFalse(validate(copyAnd(base) { put16(it, local + 28, 0xFFFF) }))
    }

    @Test fun inconsistentCountsAndDirectoryBoundsAreRejected() {
        val base = zipBytes("a.txt" to "a".toByteArray())
        val eocd = eocdOffset(base)
        val cdSize = read32(base, eocd + 12)
        val cdOffset = read32(base, eocd + 16)

        assertFalse(validate(copyAnd(base) { put16(it, eocd + 4, 1) }))
        assertFalse(validate(copyAnd(base) { put16(it, eocd + 6, 1) }))
        assertFalse(validate(copyAnd(base) { put16(it, eocd + 10, 2) }))
        assertFalse(validate(copyAnd(base) {
            put16(it, eocd + 8, 0)
            put16(it, eocd + 10, 0)
        }))
        assertFalse(validate(copyAnd(base) { put32(it, eocd + 12, cdSize + 1) }))
        assertFalse(validate(copyAnd(base) { put32(it, eocd + 12, cdSize - 1) }))
        assertFalse(validate(copyAnd(base) { put32(it, eocd + 16, cdOffset + 1) }))
        assertFalse(validate(copyAnd(base) { put32(it, eocd + 16, 0xFFFFFF00L) }))
        assertFalse(validate(copyAnd(base) { put32(it, eocd + 12, 0xFFFFFFFFL) }))
    }

    @Test fun sentinelFieldsWithoutZIP64RecordsDoNotBypassValidation() {
        val base = zipBytes("a.txt" to "a".toByteArray())
        val eocd = eocdOffset(base)
        val corruptions: List<(ByteArray) -> Unit> = listOf(
            { put32(it, eocd + 16, 0xFFFFFFFFL) },
            { put32(it, eocd + 12, 0xFFFFFFFFL) },
            { put16(it, eocd + 8, 0xFFFF) },
            { put16(it, eocd + 10, 0xFFFF) },
        )
        for (corrupt in corruptions) {
            assertFalse(validate(copyAnd(base, corrupt)))
        }
    }

    @Test fun sparseZip64ArchiveAboveFourGiBIsValid() {
        val archive = sparseZip64()
        assertTrue(archive.size > (1L shl 32))
        assertTrue(ZipArchiveValidator.validate(archive))
    }

    @Test fun sparseZip64WithZIP64SizesAndCountsIsValid() {
        val archive = sparseZip64(wideSizesAndCounts = true)
        assertTrue(archive.size > (1L shl 32))
        assertTrue(ZipArchiveValidator.validate(archive))
    }

    @Test fun damagedSparseZip64ArchiveIsRejected() {
        assertFalse(ZipArchiveValidator.validate(sparseZip64(badLocatorDisks = true)))
        assertFalse(ZipArchiveValidator.validate(sparseZip64(badZip64CdOffset = true)))
        assertFalse(ZipArchiveValidator.validate(sparseZip64(badLocalOffset = true)))
    }

    @Test fun failingShortOrImpossibleReadsReturnFalse() {
        assertFalse(ZipArchiveValidator.validate(object : ArchiveBytes {
            override val size = 22L
            override fun read(offset: Long, length: Int): ByteArray =
                throw IllegalStateException("unreadable")
        }))
        assertFalse(ZipArchiveValidator.validate(object : ArchiveBytes {
            override val size = 4096L
            override fun read(offset: Long, length: Int): ByteArray = ByteArray(0)
        }))
        assertFalse(ZipArchiveValidator.validate(object : ArchiveBytes {
            override val size = -1L
            override fun read(offset: Long, length: Int): ByteArray = ByteArray(length)
        }))
    }

    private fun validate(bytes: ByteArray): Boolean =
        ZipArchiveValidator.validate(ByteArchive(bytes))

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (data.isNotEmpty()) zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun appendComment(base: ByteArray, comment: ByteArray): ByteArray {
        require(comment.size <= 0xFFFF)
        val eocd = eocdOffset(base)
        require(eocd + 22 == base.size)
        val result = base.copyOf(base.size + comment.size)
        put16(result, eocd + 20, comment.size)
        comment.copyInto(result, base.size)
        return result
    }

    private fun sparseZip64(
        badLocatorDisks: Boolean = false,
        badZip64CdOffset: Boolean = false,
        badLocalOffset: Boolean = false,
        wideSizesAndCounts: Boolean = false,
    ): SparseArchive {
        val name = "large.bin".toByteArray(Charsets.US_ASCII)
        val localOffset = (1L shl 32) + 4097

        val local = ByteArray(30 + name.size).also { header ->
            put32(header, 0, LOCAL_SIGNATURE)
            put16(header, 4, 45)
            put16(header, 6, 0)
            put16(header, 8, 0)
            put16(header, 10, 0)
            put16(header, 12, 0)
            put32(header, 14, 0) // CRC
            put32(header, 18, 0) // compressed size
            put32(header, 22, 0) // uncompressed size
            put16(header, 26, name.size)
            put16(header, 28, 0)
            name.copyInto(header, 30)
        }

        val extra = if (wideSizesAndCounts) ByteArray(20) else ByteArray(12)
        put16(extra, 0, 0x0001)
        put16(extra, 2, extra.size - 4)
        var extraPosition = 4
        if (wideSizesAndCounts) {
            put64(extra, extraPosition, 0) // compressed size, in ZIP64 field order
            extraPosition += 8
        }
        put64(extra, extraPosition, if (badLocalOffset) localOffset + 1 else localOffset)

        val centralOffset = localOffset + local.size + 250_000L
        val central = ByteArray(46 + name.size + extra.size).also { header ->
            put32(header, 0, CENTRAL_SIGNATURE)
            put16(header, 4, 45)
            put16(header, 6, 45)
            put16(header, 8, 0)
            put16(header, 10, 0)
            put16(header, 12, 0)
            put16(header, 14, 0)
            put32(header, 16, 0) // CRC
            put32(header, 20, if (wideSizesAndCounts) 0xFFFFFFFFL else 0) // compressed size
            put32(header, 24, 0) // uncompressed size
            put16(header, 28, name.size)
            put16(header, 30, extra.size)
            put16(header, 32, 0)
            put16(header, 34, 0)
            put16(header, 36, 0)
            put32(header, 38, 0)
            put32(header, 42, 0xFFFFFFFFL) // replaced by the ZIP64 extra field
            name.copyInto(header, 46)
            extra.copyInto(header, 46 + name.size)
        }

        val zip64EocdOffset = centralOffset + central.size
        val zip64Eocd = ByteArray(56).also { record ->
            put32(record, 0, ZIP64_EOCD_SIGNATURE)
            put64(record, 4, 44)
            put16(record, 12, 45)
            put16(record, 14, 45)
            put32(record, 16, 0)
            put32(record, 20, 0)
            put64(record, 24, 1)
            put64(record, 32, 1)
            put64(record, 40, central.size.toLong())
            put64(record, 48, if (badZip64CdOffset) centralOffset + 1 else centralOffset)
        }

        val locatorOffset = zip64EocdOffset + zip64Eocd.size
        val locator = ByteArray(20).also { record ->
            put32(record, 0, ZIP64_LOCATOR_SIGNATURE)
            put32(record, 4, 0)
            put64(record, 8, zip64EocdOffset)
            put32(record, 16, if (badLocatorDisks) 2 else 1)
        }

        val eocdOffset = locatorOffset + locator.size
        val eocd = ByteArray(22).also { record ->
            put32(record, 0, EOCD_SIGNATURE)
            put16(record, 4, 0)
            put16(record, 6, 0)
            put16(record, 8, if (wideSizesAndCounts) 0xFFFF else 1)
            put16(record, 10, if (wideSizesAndCounts) 0xFFFF else 1)
            put32(record, 12, if (wideSizesAndCounts) 0xFFFFFFFFL else central.size.toLong())
            put32(record, 16, 0xFFFFFFFFL)
            put16(record, 20, 0)
        }

        return SparseArchive(
            size = eocdOffset + eocd.size,
            segments = mapOf(
                localOffset to local,
                centralOffset to central,
                zip64EocdOffset to zip64Eocd,
                locatorOffset to locator,
                eocdOffset.toLong() to eocd,
            ),
        )
    }

    private fun copyAnd(source: ByteArray, change: (ByteArray) -> Unit): ByteArray {
        val result = source.copyOf()
        change(result)
        return result
    }

    private fun eocdOffset(bytes: ByteArray): Int {
        for (index in bytes.size - 22 downTo 0) {
            if (read32(bytes, index) == EOCD_SIGNATURE &&
                index + 22 + read16(bytes, index + 20) == bytes.size
            ) {
                return index
            }
        }
        throw AssertionError("EOCD not found")
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun read32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private class ByteArchive(private val bytes: ByteArray) : ArchiveBytes {
        override val size: Long = bytes.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset <= size - length)
            val start = offset.toInt()
            return bytes.copyOfRange(start, start + length)
        }
    }

    private class SparseArchive(
        override val size: Long,
        private val segments: Map<Long, ByteArray>,
    ) : ArchiveBytes {
        override fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset <= size - length)
            val result = ByteArray(length)
            for ((segmentStart, data) in segments) {
                val from = maxOf(offset, segmentStart)
                val until = minOf(offset + length, segmentStart + data.size)
                if (from < until) {
                    data.copyInto(
                        destination = result,
                        destinationOffset = (from - offset).toInt(),
                        startIndex = (from - segmentStart).toInt(),
                        endIndex = (until - segmentStart).toInt(),
                    )
                }
            }
            return result
        }
    }
}
