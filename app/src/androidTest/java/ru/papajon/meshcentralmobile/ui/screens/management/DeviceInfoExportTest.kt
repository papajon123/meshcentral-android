package ru.papajon.meshcentralmobile.ui.screens.management

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DeviceInfoReport
import ru.papajon.meshcentralmobile.management.DeviceInfoWriter
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DeviceInfoExportTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fullSnapshot = DeviceSystemSnapshot(
        deviceName = "Synthetic PC",
        osName = "Windows 11",
        osVersion = "10.0.26100",
        architecture = "x86_64",
        kernelRelease = "10.0.26100",
        cpu = "Synthetic CPU",
        model = "Synthetic Model",
        hardwareDetails = ru.papajon.meshcentralmobile.management.HardwareDetails(biosVendor = "Synthetic BIOS", boardName = "Synthetic Board", tpmEnabled = false),
        memoryModules = listOf(
            DeviceSystemSnapshot.MemoryModule(capacityBytes = 8L * 1024 * 1024 * 1024, speed = "3200"),
            DeviceSystemSnapshot.MemoryModule(capacityBytes = 8L * 1024 * 1024 * 1024, speed = "3200"),
        ),
        disks = listOf(
            DeviceSystemSnapshot.Disk(
                name = "Disk 0",
                model = "Synthetic NVMe",
                capacityBytes = 512L * 1024 * 1024 * 1024,
            ),
        ),
        networkAddresses = listOf(
            DeviceSystemSnapshot.NetworkAddress(
                interfaceName = "eth0",
                address = "192.0.2.10",
                family = "IPv4",
            ),
        ),
    )

    private val partialSnapshot = DeviceSystemSnapshot(
        deviceName = "Synthetic PC",
        networkAddresses = listOf(
            DeviceSystemSnapshot.NetworkAddress(address = "2001:db8::1"),
        ),
    )

    @Test
    fun fullSnapshotIsLocalizedAndFormattedInRussian() {
        assertFullReport(localized("ru"))
    }

    @Test
    fun fullSnapshotIsLocalizedAndFormattedInEnglish() {
        assertFullReport(localized("en"))
    }

    @Test
    fun partialSnapshotKeepsEmptySectionsLocalizedInRussian() {
        assertPartialReport(localized("ru"))
    }

    @Test
    fun partialSnapshotKeepsEmptySectionsLocalizedInEnglish() {
        assertPartialReport(localized("en"))
    }

    @Test
    fun copyWritesTheSameReportToTheRealClipboard() {
        lateinit var clipboard: ClipboardManager
        compose.runOnUiThread { clipboard = requireNotNull(compose.activity.getSystemService(ClipboardManager::class.java)) }
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceInfoActions(snapshot = fullSnapshot, modifier = Modifier)
            }
        }

        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "synthetic-before"))
        }
        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoCopy).performClick()
        compose.waitForIdle()

        var copied: String? = null
        compose.runOnIdle {
            copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(DeviceInfoReport.format(compose.activity, fullSnapshot), copied)
    }

    @Test
    fun emptySnapshotDoesNotShowActions() {
        compose.setContent {
            MeshCentralMobileTheme {
                DeviceInspectorScreen(
                    snapshot = DeviceSystemSnapshot(),
                    services = emptyList(),
                    processes = emptyList(),
                    loading = false,
                    error = null,
                    availablePowerActions = emptySet(),
                    canManageServices = false,
                    onRefresh = {},
                    onLoadServices = {},
                    onLoadProcesses = {},
                    onPower = {},
                    onServiceAction = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoActions).assertDoesNotExist()
        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoCopy).assertDoesNotExist()
        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoSave).assertDoesNotExist()
    }

    @Test
    fun compactActionsFitOneRowAndAreCaptured() {
        compose.setContent {
            MeshCentralMobileTheme {
                Surface(
                    modifier = Modifier
                        .padding(8.dp)
                        .testTag("device_info_panel"),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    DeviceInfoActions(snapshot = fullSnapshot, modifier = Modifier)
                }
            }
        }

        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoActions).assertIsDisplayed()
        val copy = compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoCopy).getUnclippedBoundsInRoot()
        val save = compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoSave).getUnclippedBoundsInRoot()
        assertTrue("Copy must stay left of Save in the same FlowRow", copy.right <= save.left)
        assertTrue("Copy target must be at least 48dp high", copy.bottom - copy.top >= 48.dp)
        assertTrue("Save target must be at least 48dp high", save.bottom - save.top >= 48.dp)

        val bitmap = compose.onNodeWithTag("device_info_panel")
            .captureToImage()
            .asAndroidBitmap()
        assertTrue("Panel screenshot must contain pixels", bitmap.width > 0 && bitmap.height > 0)
        File(targetContext.cacheDir, "device-info-actions.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun writerWritesUtf8AndReadsItBack() {
        assertEquals("device-info.txt", DeviceInfoReport.FILE_NAME)
        assertEquals("text/plain", DeviceInfoReport.MIME_TYPE)
        val file = File(targetContext.cacheDir, "device-info-writer-${System.nanoTime()}.txt")
        try {
            val text = DeviceInfoReport.format(localized("ru"), fullSnapshot)
            assertTrue(DeviceInfoWriter.write({ FileOutputStream(file) }, text))
            assertEquals(text, file.readText(Charsets.UTF_8))
        } finally {
            file.delete()
        }
    }

    @Test
    fun writerReportsNullAndIoFailuresAsFalse() {
        assertFalse(DeviceInfoWriter.write({ null }, "synthetic"))
        assertFalse(DeviceInfoWriter.write({ throw IOException("synthetic") }, "synthetic"))
        assertFalse(DeviceInfoWriter.write({ throw SecurityException("synthetic") }, "synthetic"))
    }

    @Test
    fun writerReportsCloseFailureAsFalse() {
        val stream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                throw IOException("synthetic close")
            }
        }
        assertFalse(DeviceInfoWriter.write({ stream }, "synthetic"))
    }

    @Test
    fun writerPreservesCancellation() {
        var cancelled = false
        try {
            DeviceInfoWriter.write({ throw CancellationException("synthetic") }, "synthetic")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("CancellationException must not become a generic write failure", cancelled)
    }

    private fun assertFullReport(context: Context) {
        val report = DeviceInfoReport.format(context, fullSnapshot)
        assertTrue(report.startsWith(context.getString(R.string.manage_details_subtitle)))
        listOf(
            R.string.manage_section_system,
            R.string.manage_section_hardware,
            R.string.manage_section_memory,
            R.string.manage_section_disks,
            R.string.manage_section_network,
        ).forEach { title ->
            assertTrue(context.getString(title), report.contains(context.getString(title)))
        }
        val total = Formatter.formatFileSize(context, 16L * 1024 * 1024 * 1024)
        assertTrue(report.contains(context.getString(R.string.manage_memory_total, total)))
        listOf(
            "Synthetic PC",
            "Windows 11",
            "x86_64",
            "Synthetic CPU",
            "Synthetic Model",
            "Synthetic NVMe",
            "Disk 0",
            "eth0 · IPv4",
            "192.0.2.10",
        ).forEach { value -> assertTrue(value, report.contains(value)) }
        assertTrue(report.contains("${context.getString(R.string.manage_memory_speed)}: 3200"))
    }

    private fun assertPartialReport(context: Context) {
        val report = DeviceInfoReport.format(context, partialSnapshot)
        assertTrue(report.contains(context.getString(R.string.manage_memory_empty)))
        assertTrue(report.contains(context.getString(R.string.manage_disks_empty)))
        assertFalse(report.contains(context.getString(R.string.manage_section_hardware)))
        assertFalse(report.contains(context.getString(R.string.manage_not_reported)))
        assertTrue(report.contains(context.getString(R.string.manage_label_interface)))
        assertTrue(report.contains("2001:db8::1"))
        assertTrue(report.contains("Synthetic PC"))
    }

    private fun localized(languageTag: String): Context {
        val configuration = Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return targetContext.createConfigurationContext(configuration)
    }
}
