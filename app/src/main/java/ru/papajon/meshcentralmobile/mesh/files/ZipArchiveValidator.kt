package ru.papajon.meshcentralmobile.mesh.files

/**
 * Random-access view over an archive. [read] must return exactly [length] bytes and must not
 * allocate the whole archive; the validator treats short reads, exceptions, and out-of-range
 * requests as malformed input.
 */
internal interface ArchiveBytes {
    val size: Long

    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Pure structure validator for ZIP and ZIP64 archives. It never decompresses data and never
 * allocates an archive-sized buffer, so it can validate archives larger than 4 GiB through a
 * sparse [ArchiveBytes] implementation.
 *
 * The EOCD must end exactly at EOF (a normal ZIP comment is allowed), every central-directory
 * record and referenced local header must exist and agree, and multi-disk archives are rejected.
 * When an EOCD field contains a sentinel and a ZIP64 locator is present, the ZIP64 records are
 * parsed and cross-checked; without a locator the sentinel is treated as the literal maximum
 * (for example 65,535 entries).
 *
 * Resource bounds: at most [MAX_ENTRIES] entries and [MAX_METADATA_BYTES] bytes of headers,
 * names, extras, and trailers are read. Central-directory comments are skipped, never loaded.
 */
internal object ZipArchiveValidator {
    /**
     * Upper bound that keeps validation work predictable on a phone. Archives with more entries
     * are rejected; the limit matches the largest normal EOCD count (65,535).
     */
    const val MAX_ENTRIES = 65_535

    /** Total budget for metadata reads from an untrusted source. */
    const val MAX_METADATA_BYTES = 32 * 1024 * 1024

    private const val MAX_COMMENT_BYTES = 65_535
    private const val MAX_EOCD_CANDIDATES = 256

    private const val EOCD_MIN_SIZE = 22
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_MIN_SIZE = 56
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val DIGITAL_SIGNATURE = 0x05054b50L

    private const val SENTINEL_16 = 0xFFFF
    private const val SENTINEL_32 = 0xFFFFFFFFL
    private const val ZIP64_EXTRA_ID = 0x0001

    private val EMPTY = ByteArray(0)

    fun validate(source: ArchiveBytes): Boolean = try {
        Session(source).run()
    } catch (_: Exception) {
        false
    }

    private class MalformedArchive : Exception()

    private fun malformed(): Nothing = throw MalformedArchive()

    private class Session(private val source: ArchiveBytes) {
        private val fileSize = source.size
        private var metadataBudget = MAX_METADATA_BYTES

        fun run(): Boolean {
            if (fileSize < EOCD_MIN_SIZE) return false

            // The comment is at most 65,535 bytes, so a complete EOCD must start in this tail.
            val tailLength = minOf(fileSize, EOCD_MIN_SIZE.toLong() + MAX_COMMENT_BYTES).toInt()
            val tailStart = fileSize - tailLength
            val tail = read(tailStart, tailLength)

            var candidates = 0
            var index = tailLength - EOCD_MIN_SIZE
            while (index >= 0) {
                if (u32(tail, index) == EOCD_SIGNATURE &&
                    index + EOCD_MIN_SIZE + u16(tail, index + 20) == tailLength
                ) {
                    candidates++
                    if (candidates > MAX_EOCD_CANDIDATES) return false
                    try {
                        // A signature may be part of the comment; keep looking for the real EOCD.
                        if (validateFromEocd(tailStart + index)) return true
                    } catch (_: MalformedArchive) {
                        // Try the next earlier candidate.
                    }
                }
                index--
            }
            return false
        }

        private fun validateFromEocd(eocdOffset: Long): Boolean {
            if (eocdOffset < 0 || eocdOffset > fileSize - EOCD_MIN_SIZE) malformed()
            val eocd = read(eocdOffset, EOCD_MIN_SIZE)
            if (u32(eocd, 0) != EOCD_SIGNATURE) malformed()

            val commentLength = u16(eocd, 20)
            if (eocdOffset != fileSize - EOCD_MIN_SIZE - commentLength) malformed()

            if (u16(eocd, 4) != 0 || u16(eocd, 6) != 0) malformed() // multi-disk archive
            val entriesOnDisk16 = u16(eocd, 8)
            val entriesTotal16 = u16(eocd, 10)
            val cdSize32 = u32(eocd, 12)
            val cdOffset32 = u32(eocd, 16)

            val needsZip64 = entriesOnDisk16 == SENTINEL_16 || entriesTotal16 == SENTINEL_16 ||
                cdSize32 == SENTINEL_32 || cdOffset32 == SENTINEL_32

            var entries = 0L
            var cdSize = 0L
            var cdOffset = 0L
            var boundary = 0L
            var usedZip64 = false

            if (needsZip64 && eocdOffset >= ZIP64_LOCATOR_SIZE) {
                val locatorOffset = eocdOffset - ZIP64_LOCATOR_SIZE
                val locator = read(locatorOffset, ZIP64_LOCATOR_SIZE)
                if (u32(locator, 0) == ZIP64_LOCATOR_SIGNATURE) {
                    if (u32(locator, 4) != 0L) malformed() // disk holding the ZIP64 EOCD
                    val zip64EocdOffset = u64(locator, 8)
                    if (u32(locator, 16) != 1L) malformed() // total number of disks

                    if (zip64EocdOffset > locatorOffset - ZIP64_EOCD_MIN_SIZE) malformed()
                    val zip64 = read(zip64EocdOffset, ZIP64_EOCD_MIN_SIZE)
                    if (u32(zip64, 0) != ZIP64_EOCD_SIGNATURE) malformed()

                    val recordSize = u64(zip64, 4)
                    val available = locatorOffset - zip64EocdOffset - 12
                    // The ZIP64 EOCD is immediately followed by its locator.
                    if (recordSize < 44 || recordSize != available) malformed()

                    if (u32(zip64, 16) != 0L || u32(zip64, 20) != 0L) malformed()
                    val zip64EntriesOnDisk = u64(zip64, 24)
                    val zip64EntriesTotal = u64(zip64, 32)
                    val zip64CdSize = u64(zip64, 40)
                    val zip64CdOffset = u64(zip64, 48)

                    if (zip64EntriesOnDisk != zip64EntriesTotal) malformed()
                    if (zip64EntriesTotal > MAX_ENTRIES) malformed()
                    if (entriesOnDisk16 != SENTINEL_16 &&
                        entriesOnDisk16.toLong() != zip64EntriesOnDisk
                    ) malformed()
                    if (entriesTotal16 != SENTINEL_16 &&
                        entriesTotal16.toLong() != zip64EntriesTotal
                    ) malformed()
                    if (cdSize32 != SENTINEL_32 && cdSize32 != zip64CdSize) malformed()
                    if (cdOffset32 != SENTINEL_32 && cdOffset32 != zip64CdOffset) malformed()

                    entries = zip64EntriesTotal
                    cdSize = zip64CdSize
                    cdOffset = zip64CdOffset
                    boundary = zip64EocdOffset
                    usedZip64 = true
                }
            }

            if (!usedZip64) {
                // 0xFFFF/0xFFFFFFFF are also the largest literal values (e.g. 65,535 entries).
                if (entriesOnDisk16 != entriesTotal16) malformed()
                entries = entriesTotal16.toLong()
                if (entries > MAX_ENTRIES) malformed()
                cdSize = cdSize32
                cdOffset = cdOffset32
                boundary = eocdOffset
            }

            if (cdOffset < 0 || cdSize < 0 || cdOffset > boundary) malformed()
            if (cdSize > boundary - cdOffset) malformed()
            val cdEnd = cdOffset + cdSize

            parseCentralDirectory(cdOffset, cdEnd, entries)

            // A digital-signature record may legally follow the central directory.
            if (cdEnd < boundary) validateTrailer(cdEnd, boundary)
            return true
        }

        private fun parseCentralDirectory(cdOffset: Long, cdEnd: Long, entries: Long) {
            var cursor = cdOffset
            var index = 0L
            val localOffsets = HashSet<Long>()

            while (index < entries) {
                if (cursor > cdEnd - CENTRAL_HEADER_SIZE) malformed()
                val header = read(cursor, CENTRAL_HEADER_SIZE)
                if (u32(header, 0) != CENTRAL_SIGNATURE) malformed()

                val flags = u16(header, 8)
                val method = u16(header, 10)
                val compressedSize32 = u32(header, 20)
                val uncompressedSize32 = u32(header, 24)
                val nameLength = u16(header, 28)
                val extraLength = u16(header, 30)
                val commentLength = u16(header, 32)
                val diskStart16 = u16(header, 34)
                val localOffset32 = u32(header, 42)

                if (nameLength == 0) malformed()
                val recordLength = CENTRAL_HEADER_SIZE.toLong() + nameLength + extraLength + commentLength
                if (recordLength > cdEnd - cursor) malformed()

                val metadataLength = nameLength + extraLength
                val metadata = read(cursor + CENTRAL_HEADER_SIZE, metadataLength)
                val name = metadata.copyOfRange(0, nameLength)
                val extra = if (extraLength == 0) EMPTY
                else metadata.copyOfRange(nameLength, metadataLength)
                // Validate extra-field framing even when no ZIP64 value is required.
                val zip64Extra = if (extraLength == 0) null else findZip64Extra(extra)

                val needsZip64 = compressedSize32 == SENTINEL_32 ||
                    uncompressedSize32 == SENTINEL_32 ||
                    localOffset32 == SENTINEL_32 || diskStart16 == SENTINEL_16

                var compressedSize = compressedSize32
                var localOffset = localOffset32
                var diskStart = diskStart16.toLong()

                if (needsZip64) {
                    if (zip64Extra == null) malformed()
                    var position = 0
                    if (uncompressedSize32 == SENTINEL_32) {
                        if (position > zip64Extra.size - 8) malformed()
                        position += 8
                    }
                    if (compressedSize32 == SENTINEL_32) {
                        compressedSize = zip64Value(zip64Extra, position)
                        position += 8
                    }
                    if (localOffset32 == SENTINEL_32) {
                        localOffset = zip64Value(zip64Extra, position)
                        position += 8
                    }
                    if (diskStart16 == SENTINEL_16) {
                        if (position > zip64Extra.size - 4) malformed()
                        diskStart = u32(zip64Extra, position)
                    }
                }
                if (diskStart != 0L) malformed() // multi-disk archive

                validateLocalEntry(
                    name = name,
                    flags = flags,
                    method = method,
                    compressedSize = compressedSize,
                    localOffset = localOffset,
                    cdOffset = cdOffset,
                    localOffsets = localOffsets,
                )

                cursor += recordLength
                index++
            }

            // Some writers include the digital signature in the central-directory size.
            if (cursor < cdEnd) validateTrailer(cursor, cdEnd)
        }

        private fun validateLocalEntry(
            name: ByteArray,
            flags: Int,
            method: Int,
            compressedSize: Long,
            localOffset: Long,
            cdOffset: Long,
            localOffsets: MutableSet<Long>,
        ) {
            if (localOffset < 0 || localOffset >= cdOffset) malformed()
            if (!localOffsets.add(localOffset)) malformed()
            if (localOffset > fileSize - LOCAL_HEADER_SIZE) malformed()

            val header = read(localOffset, LOCAL_HEADER_SIZE)
            if (u32(header, 0) != LOCAL_SIGNATURE) malformed()
            if (u16(header, 6) != flags || u16(header, 8) != method) malformed()

            val nameLength = u16(header, 26)
            val extraLength = u16(header, 28)
            if (nameLength != name.size) malformed()

            if (localOffset > cdOffset - LOCAL_HEADER_SIZE) malformed()
            val availableHeaderBytes = cdOffset - localOffset - LOCAL_HEADER_SIZE
            if (nameLength.toLong() + extraLength > availableHeaderBytes) malformed()

            val localName = read(localOffset + LOCAL_HEADER_SIZE, nameLength)
            if (!localName.contentEquals(name)) malformed()

            val dataStart = localOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
            // Compressed data must stay before the central directory; contents are not inspected.
            if (compressedSize > cdOffset - dataStart) malformed()
        }

        private fun findZip64Extra(extra: ByteArray): ByteArray? {
            var position = 0
            var result: ByteArray? = null
            while (position < extra.size) {
                if (position > extra.size - 4) malformed()
                val id = u16(extra, position)
                val length = u16(extra, position + 2)
                val dataStart = position + 4
                if (length > extra.size - dataStart) malformed()
                if (id == ZIP64_EXTRA_ID) {
                    if (result != null) malformed() // duplicate ZIP64 extra field
                    result = extra.copyOfRange(dataStart, dataStart + length)
                }
                position = dataStart + length
            }
            return result
        }

        private fun zip64Value(extra: ByteArray, position: Int): Long {
            if (position < 0 || position > extra.size - 8) malformed()
            return u64(extra, position)
        }

        private fun validateTrailer(start: Long, end: Long) {
            var position = start
            while (position < end) {
                if (position > end - 6) malformed()
                val header = read(position, 6)
                if (u32(header, 0) != DIGITAL_SIGNATURE) malformed()
                val length = u16(header, 4)
                if (length > end - position - 6) malformed()
                position += 6 + length
            }
            if (position != end) malformed()
        }

        private fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || length < 0 || offset > fileSize - length.toLong()) malformed()
            if (length > metadataBudget) malformed()
            metadataBudget -= length
            val bytes = try {
                source.read(offset, length)
            } catch (_: Exception) {
                malformed()
            }
            if (bytes.size != length) malformed()
            return bytes
        }

        private fun u16(bytes: ByteArray, index: Int): Int {
            if (index < 0 || index > bytes.size - 2) malformed()
            return (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(bytes: ByteArray, index: Int): Long {
            if (index < 0 || index > bytes.size - 4) malformed()
            return (bytes[index].toLong() and 0xFF) or
                ((bytes[index + 1].toLong() and 0xFF) shl 8) or
                ((bytes[index + 2].toLong() and 0xFF) shl 16) or
                ((bytes[index + 3].toLong() and 0xFF) shl 24)
        }

        /** Reads an unsigned 64-bit value; values above [Long.MAX_VALUE] cannot fit a file. */
        private fun u64(bytes: ByteArray, index: Int): Long {
            if (index < 0 || index > bytes.size - 8) malformed()
            var value = 0L
            for (shift in 0 until 8) {
                value = value or ((bytes[index + shift].toLong() and 0xFF) shl (shift * 8))
            }
            if (value < 0) malformed()
            return value
        }
    }
}
