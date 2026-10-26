package ru.papajon.meshcentralmobile.mesh.files

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentFilesProtocolTest {
    @Test fun mediaDuplicatesRemainVisibleButCannotAddressTheWrongFile() {
        // MeshTunnel.getFolder in both 1.0.23 and main provides n/t/s/d, no path or media ID.
        val response = JSONObject("""{"path":"Images","dir":[{"n":"same.jpg","t":3,"s":4,"d":12},{"n":"same.jpg","t":3,"s":9,"d":13},{"n":"unique.jpg","t":3,"s":1,"d":14}]}""")
        val entries = AgentFilesProtocol.entries(response, phoneMedia = true)
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.listingIndex })
        assertEquals(listOf(4L, 9L), entries.filter { it.ambiguousName }.map { it.size })
        assertTrue(entries.filter { it.name == "same.jpg" }.none { it.actionable })
        assertTrue(entries.single { it.name == "unique.jpg" }.actionable)
    }
    @Test fun androidCategoriesStayRelativeAndReturnToTheVirtualRoot() {
        assertEquals("Images", AgentFilesProtocol.join("", "Images"))
        assertEquals("Sdcard/Download", AgentFilesProtocol.join("Sdcard", "Download"))
        assertEquals("", AgentFilesProtocol.parent("Images"))
        assertEquals("Sdcard", AgentFilesProtocol.parent("Sdcard/Download"))
    }

    @Test fun jsonBudgetMatchesOneMiBTransportAndCountsUtf8Bytes() {
        assertEquals(1_048_576, AgentFilesProtocol.MAX_JSON_BYTES)
        assertTrue(AgentFilesProtocol.jsonWithinLimit("a".repeat(1_048_576)))
        assertFalse(AgentFilesProtocol.jsonWithinLimit("a".repeat(1_048_577)))
        assertTrue(AgentFilesProtocol.jsonWithinLimit("я".repeat(524_288)))
        assertFalse(AgentFilesProtocol.jsonWithinLimit("я".repeat(524_289)))
    }
    @Test fun windowsDriveAndUnixParentsRemainAbsolute() {
        assertEquals("C:/", AgentFilesProtocol.join("", "C:"))
        assertEquals("C:/", AgentFilesProtocol.parent("C:/Users"))
        assertEquals("", AgentFilesProtocol.parent("C:/"))
        assertEquals("/", AgentFilesProtocol.parent("/home"))
        assertEquals("/home/файл.txt", AgentFilesProtocol.join("/home/", "файл.txt"))
    }

    @Test fun uploadEscapesEveryByteValueWithoutLoss() {
        for (first in 0..255) {
            val original = byteArrayOf(first.toByte(), 0, 123, 42)
            val encoded = AgentFilesProtocol.uploadFrame(original, original.size)
            assertEquals(0, encoded[0].toInt())
            assertArrayEquals(original, encoded.copyOfRange(1, encoded.size))
        }
    }

    @Test fun finalHeaderWorksForEmptyAndExactMultipleFiles() {
        assertTrue(AgentFilesProtocol.downloadFinal(byteArrayOf(1, 0, 0, 1)))
        val full = ByteArray(16_384).also { it[0] = 1 }
        assertFalse(AgentFilesProtocol.downloadFinal(full))
        // Agent sends a separate empty final block when the last read was exactly 16,380 bytes.
        assertTrue(AgentFilesProtocol.downloadFinal(byteArrayOf(1, 0, 0, 1)))
    }

    @Test fun rejectsMalformedAndOversizedDownloadFrames() {
        listOf(byteArrayOf(), byteArrayOf(1, 0, 1, 0), byteArrayOf(1, 0, 0, 2), ByteArray(16_385)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AgentFilesProtocol.downloadFinal(it) }
        }
    }

    @Test fun parsesDriveFolderAndUnicodeFileFixture() {
        val fixture = JSONObject("""{"path":"","reqid":4,"dir":[{"n":"C:","t":1,"s":100},{"n":"Папка","t":2},{"n":"資料.txt","t":3,"s":9223372036854}]}""")
        val result = AgentFilesProtocol.entries(fixture)
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.directory })
        assertEquals(9_223_372_036_854L, result.single { !it.directory }.size)
    }

    @Test fun permissionFailureIsNotAnEmptyDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentFilesProtocol.entries(JSONObject("""{"path":"/denied","dir":null,"reqid":3}"""))
        }
        assertTrue(AgentFilesProtocol.entries(JSONObject("""{"path":"/empty","dir":[],"reqid":4}""")).isEmpty())
    }

    @Test fun duplicateTraversalAndOversizedListingAreRejected() {
        listOf("..", "../target", "C:/target", "a\\b", "").forEach { assertFalse(AgentFilesProtocol.validName(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            AgentFilesProtocol.entries(JSONObject("""{"dir":[{"n":"same","t":3},{"n":"same","t":3}]}"""))
        }
        val list = JSONArray()
        repeat(AgentFilesProtocol.MAX_LIST_ENTRIES + 1) { list.put(JSONObject().put("n", "$it").put("t", 3)) }
        assertThrows(IllegalArgumentException::class.java) { AgentFilesProtocol.entries(JSONObject().put("dir", list)) }
    }

    @Test fun existingPosixColonNamesAreReadableButNotAcceptedAsNewNames() {
        val entries = AgentFilesProtocol.entries(JSONObject("""{"dir":[{"n":"0000:00:00.0","t":2},{"n":"backup-12:00.tar","t":3,"s":7}]}"""))
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.actionable })
        assertEquals("/sys/bus/pci/devices/0000:00:00.0", AgentFilesProtocol.join("/sys/bus/pci/devices", entries.first().name))
        assertFalse(AgentFilesProtocol.validName("backup-12:00.tar"))
    }

    @Test fun unsupportedBackslashAndControlEntriesDoNotHideOrdinaryFiles() {
        val directory = JSONArray()
        listOf("ordinary.txt", "a\\b", "line\nbreak", "rtl\u202ename").forEach {
            directory.put(JSONObject().put("n", it).put("t", 3).put("s", 0))
        }
        val entries = AgentFilesProtocol.entries(JSONObject().put("dir", directory))
        assertEquals(4, entries.size)
        assertTrue(entries.single { it.name == "ordinary.txt" }.actionable)
        assertTrue(entries.filter { it.name != "ordinary.txt" }.none { it.actionable })
        assertEquals("line\\u000abreak", entries.single { it.name.contains('\n') }.displayName)
        assertEquals("rtl\\u202ename", entries.single { it.name.contains('\u202e') }.displayName)
    }

    @Test fun nulSlashAndDuplicateComponentsAreRejectedAndDotEntriesCannotTraverse() {
        listOf("nul\u0000name", "dir/file", "../secret", "/etc", "").forEach { name ->
            val directory = JSONArray().put(JSONObject().put("n", name).put("t", 3).put("s", 1))
            assertThrows(IllegalArgumentException::class.java) { AgentFilesProtocol.entries(JSONObject().put("dir", directory)) }
        }
        val dotEntries = JSONObject("""{"dir":[{"n":".","t":2},{"n":"..","t":2},{"n":"safe","t":2}]}""")
        assertEquals(listOf("safe"), AgentFilesProtocol.entries(dotEntries).map { it.name })
        val duplicates = JSONObject("""{"dir":[{"n":"a:b","t":2},{"n":"a:b","t":2}]}""")
        assertThrows(IllegalArgumentException::class.java) { AgentFilesProtocol.entries(duplicates) }
    }

    @Test fun unknownOrInvalidFileSizeDoesNotBecomeAConfirmedEmptyFile() {
        val entries = AgentFilesProtocol.entries(JSONObject("""{"dir":[{"n":"missing","t":3},{"n":"negative","t":3,"s":-1},{"n":"empty","t":3,"s":0}]}"""))
        assertTrue(entries.single { it.name == "empty" }.sizeKnown)
        assertTrue(entries.filter { it.name != "empty" }.none { it.sizeKnown || it.actionable })
    }
}
