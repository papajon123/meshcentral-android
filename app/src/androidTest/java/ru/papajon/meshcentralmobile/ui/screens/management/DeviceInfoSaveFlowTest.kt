package ru.papajon.meshcentralmobile.ui.screens.management

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.management.DeviceInfoReport
import ru.papajon.meshcentralmobile.management.DeviceInfoWriter
import ru.papajon.meshcentralmobile.management.DeviceSystemSnapshot
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.io.IOException
import java.io.OutputStream

class DeviceInfoSaveFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var requestCode = -1
    private var requestIntent: Intent? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            requestCode = code
            requestIntent = contract.createIntent(compose.activity, input)
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }
    private val snapshot = mutableStateOf(DeviceSystemSnapshot(deviceName = "Original synthetic computer"))

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MeshCentralMobileTheme { DeviceInfoActions(snapshot.value) }
            }
        }
        compose.onNodeWithTag(DeviceInspectorTestTags.DeviceInfoSave).performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, requestIntent?.action)
            assertEquals("text/plain", requestIntent?.type)
            assertEquals("device-info.txt", requestIntent?.getStringExtra(Intent.EXTRA_TITLE))
        }
    }

    @Test fun pickerReturnWritesTheCapturedSnapshotThroughContentResolver() {
        val original = DeviceInfoReport.format(compose.activity, snapshot.value)
        val file = File(compose.activity.cacheDir, "info-picker-${System.nanoTime()}.txt")
        try {
            show()
            compose.runOnIdle { snapshot.value = DeviceSystemSnapshot(deviceName = "Changed computer") }
            compose.waitForIdle()
            compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))) }
            compose.waitUntil(5000) { runCatching { file.readText(Charsets.UTF_8) == original }.getOrDefault(false) }
            assertEquals(original, file.readText(Charsets.UTF_8))
        } finally { file.delete() }
    }

    @Test fun cancelledPickerDoesNotWriteEvenIfItReturnsAUri() {
        val file = File(compose.activity.cacheDir, "info-cancel-${System.nanoTime()}.txt")
        try {
            show()
            compose.runOnIdle { registry.dispatchResult(requestCode, Activity.RESULT_CANCELED, Intent().setData(Uri.fromFile(file))) }
            compose.waitForIdle()
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun largeTextWrapsWithinSmallPhoneWidth() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MeshCentralMobileTheme {
                    Surface(Modifier.width(320.dp).testTag("large_info_panel")) { DeviceInfoActions(snapshot.value) }
                }
            }
        }
        val panel = compose.onNodeWithTag("large_info_panel").getUnclippedBoundsInRoot()
        for (tag in listOf(DeviceInspectorTestTags.DeviceInfoCopy, DeviceInspectorTestTags.DeviceInfoSave)) {
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= panel.left && bounds.right <= panel.right)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        val bitmap = compose.onNodeWithTag("large_info_panel").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "device-info-large-text.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun closeFailureIsNotReportedAsSuccess() {
        val stream = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() { throw IOException("synthetic close failure") }
        }
        assertFalse(DeviceInfoWriter.write({ stream }, "synthetic"))
    }
}
