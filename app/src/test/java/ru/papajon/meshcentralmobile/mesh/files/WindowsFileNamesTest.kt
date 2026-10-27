package ru.papajon.meshcentralmobile.mesh.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsFileNamesTest {
    @Test fun recognizesDriveRootsAndDriveRootedPaths() {
        listOf(
            "C:", "c:", "C:\\", "C:/", "C:\\Users\\alex", "C:/Users/alex",
            "Z:\\папка\\файл.txt", "C:\\path\\with\\trailing\\",
        ).forEach { assertTrue(it, WindowsFileNames.isWindowsPath(it)) }

        listOf("", "C", "C:relative", "1:\\dir", "C :", "/tmp").forEach {
            assertFalse(it, WindowsFileNames.isWindowsPath(it))
        }
    }

    @Test fun recognizesUncAndExtendedWindowsPaths() {
        listOf(
            "\\\\server\\share",
            "\\\\server\\share\\dir\\file.txt",
            "//server/share",
            "//server/share/dir",
            "\\\\?\\C:\\dir",
            "\\\\.\\COM1",
            "\\\\[::1]\\share",
        ).forEach { assertTrue(it, WindowsFileNames.isWindowsPath(it)) }

        listOf(
            "\\server\\share", "\\\\server", "\\\\server\\", "//server", "//server/",
            "//", "\\\\", "///", "\\tmp", "tmp", "./file", "../file", "~",
        ).forEach { assertFalse(it, WindowsFileNames.isWindowsPath(it)) }
    }

    @Test fun posixPathsAreNotClassifiedAsWindows() {
        listOf("/tmp", "/", "/home/user", "/tmp/name.", "/tmp/CON", "/tmp/a:b", "tmp/name.", "name.")
            .forEach { assertFalse(it, WindowsFileNames.isWindowsPath(it)) }
    }

    @Test fun rejectsTrailingDotSpaceAndAllDotNames() {
        listOf(".", "..", "...", "....", "name.", "name ", "name..", "name  ", "name. ", "name .", " . ")
            .forEach { assertFalse(it, WindowsFileNames.safeComponent(it)) }

        listOf("...name", "..name", "name...txt", ".hidden", "name. txt").forEach {
            assertTrue(it, WindowsFileNames.safeComponent(it))
        }
    }

    @Test fun rejectsSeparatorsAdsControlsAndOversizedComponents() {
        listOf(
            "", "a/b", "a\\b", "a:b", "C:", "a\u0000b", "a\u0001b", "a\u001Fb",
            "a\u007Fb", "a\u0085b", "a\u200Bb", "a\u200Db", "a\u200Eb", "a\u202Eb",
            "a\u2066b", "a\uFEFFb", "x".repeat(256),
        ).forEach { assertFalse(it, WindowsFileNames.safeComponent(it)) }
        assertTrue(WindowsFileNames.safeComponent("x".repeat(255)))
    }

    @Test fun rejectsReservedDosDeviceNamesWithExtensionsAndSuperscriptAliases() {
        val devices = listOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }
        for (device in devices) {
            listOf(device, device.lowercase(), "$device.txt", "$device.tar.gz", "$device .txt")
                .forEach { assertFalse(it, WindowsFileNames.safeComponent(it)) }
        }

        listOf(
            "COM\u00B9", "COM\u00B2", "COM\u00B3",
            "LPT\u00B9", "LPT\u00B2", "LPT\u00B3",
            "com\u00B9.txt", "LPT\u00B3.log",
        ).forEach { assertFalse(it, WindowsFileNames.safeComponent(it)) }

        listOf(
            "COM0", "LPT0", "COM10", "LPT10", "CONSOLE", "CONIFER", "NULL",
            "AUXILIARY", "COM\u00B9\u00B2",
        ).forEach { assertTrue(it, WindowsFileNames.safeComponent(it)) }
    }

    @Test fun acceptsOrdinaryAndUnicodeWindowsNames() {
        listOf(
            "file.txt", "Папка", "файл.txt", "資料.txt", "日本語ファイル", "😀.txt",
            ".gitignore", "name...txt", "a b", "café.txt", "file(1).txt", "COM0", "LPT10",
        ).forEach { assertTrue(it, WindowsFileNames.safeComponent(it)) }
    }
}
