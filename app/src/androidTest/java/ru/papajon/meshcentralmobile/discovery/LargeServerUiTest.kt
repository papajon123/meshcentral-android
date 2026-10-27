package ru.papajon.meshcentralmobile.discovery

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import java.util.Locale

class LargeServerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun limitedPageOmitsUnseenEmptyGroupsButOffersTheirInvitations() {
        var invited: String? = null
        val groups = listOf(MeshGroup("mesh//lab", "Visible group", mtype=2, rights=MeshRights.MANAGE_COMPUTERS),
            MeshGroup("mesh//empty", "Empty group", mtype=2, rights=MeshRights.MANAGE_COMPUTERS))
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(MeshClientState(socketConnected=true,devicesCurrent=true,requiresSignIn=false,
                agentInstallAllowed=true,serverCapabilities=MeshServerCapabilities(features=0),groups=groups,
                devicePage=DevicePage(limit=25,returnedCount=1,totalCount=100,nodeIds=setOf("node//a")),
                devices=listOf(MeshDevice("node//a","mesh//lab","Computer"))),
                emptyList(),{_,_->},{},{},{},{},{},onInvite={invited=it})
        } }
        assertThrows(AssertionError::class.java) {
            compose.onNodeWithTag("devices-list").performScrollToNode(hasText("Empty group"))
        }
        compose.onNodeWithTag("devices-invite-group").performScrollTo().performClick()
        compose.onNodeWithTag("devices-invite-target-mesh//empty").assertIsDisplayed()
        compose.waitForIdle()
        val file=File(compose.activity.getExternalFilesDir(null),"review2-invite-picker.png")
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        file.outputStream().use { automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "cp ${file.absolutePath} /data/local/tmp/review2-invite-picker.png")).use { it.readBytes() }
        compose.onNodeWithTag("devices-invite-target-mesh//empty").performClick()
        assertEquals("mesh//empty",invited)
    }

    @Test fun serverCollationMatchesAreNotFilteredAgainOnThePhone() {
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(MeshClientState(socketConnected=true,devicesCurrent=true,requiresSignIn=false,
                deviceQuery=DeviceListQuery("cafe"),devicePage=DevicePage(limit=25,filter="cafe",returnedCount=1,totalCount=1,nodeIds=setOf("node//cafe")),
                devices=listOf(MeshDevice("node//cafe","mesh//lab","Café"))),emptyList(),{_,_->},{},{},{},{},{})
        } }
        compose.onNodeWithText("Café").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectionLossOffersOneRetryNoticeInsteadOfAPageErrorDuplicate() {
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(MeshClientState(socketConnected=false,devicesCurrent=false,requiresSignIn=false,
                lastError=ru.papajon.meshcentralmobile.localization.UiText.resource(ru.papajon.meshcentralmobile.R.string.runtime_connection_lost),
                devices=listOf(MeshDevice("node//a","mesh//lab","Computer"))),emptyList(),{_,_->},{},{},{},{},{})
        } }
        val retry=compose.activity.getString(ru.papajon.meshcentralmobile.R.string.connection_retry)
        compose.onNodeWithTag("devices-list").performScrollToNode(hasText(retry))
        compose.onAllNodesWithText(retry).assertCountEquals(1)
    }

    @Test fun unpagedTimeoutKeepsCachedRowsAndOffersExplicitRetry() {
        var retried=false
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(MeshClientState(socketConnected=true,devicesCurrent=true,requiresSignIn=false,
                devices=listOf(MeshDevice("node//cached","mesh//lab","Cached computer")),
                lastError=ru.papajon.meshcentralmobile.localization.UiText.resource(ru.papajon.meshcentralmobile.R.string.devices_page_failed)),
                emptyList(),{_,_->},{},{},{retried=true},{},{})
        } }
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.devices_page_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.connection_retry)).performClick()
        assertTrue(retried)
        compose.onNodeWithText("Cached computer").assertExists()
    }

    @Test fun returningToOldSearchWhileNewSearchIsPendingSendsLatestIntent() {
        val state=mutableStateOf(MeshClientState(socketConnected=true,devicesCurrent=true,requiresSignIn=false,
            deviceQuery=DeviceListQuery("alpha"),devicePage=DevicePage(limit=25,totalCount=1,returnedCount=1,filter="alpha"),
            devices=listOf(MeshDevice("node//a","mesh//lab","Alpha"))))
        val sent=mutableListOf<String>()
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(state.value,emptyList(),{_,_->},{},{},{},{},{},onServerQuery={q,c,ids ->
                sent.add(q); state.value=state.value.copy(deviceQuery=DeviceListQuery(q,c,ids))
            })
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement("beta")
        compose.waitUntil(5000) { sent.lastOrNull()=="beta" }
        compose.onNode(hasSetTextAction()).performTextReplacement("alpha")
        compose.waitUntil(5000) { sent.lastOrNull()=="alpha" }
        assertEquals(listOf("beta","alpha"),sent)
    }

    @Test fun unpagedRefreshAndReconnectKeepTheVisibleRowAtTheSamePosition() {
        val state = mutableStateOf(MeshClientState(socketConnected=true, devicesCurrent=true, requiresSignIn=false,
            devices=(0 until 2000).map { MeshDevice("node//$it","mesh//lab","Row %04d".format(it)) }))
        compose.setContent { MeshCentralMobileTheme { DevicesContent(state.value,emptyList(),{_,_->},{},{},{},{},{}) } }
        compose.onNodeWithTag("devices-list").performScrollToIndex(1003)
        val row=compose.onAllNodes(hasText("Row",substring=true)).fetchSemanticsNodes()
            .first { it.boundsInRoot.top > 200f }.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val before=compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state.value=state.value.copy(devicePage=state.value.devicePage.copy(loading=true)) }
        compose.onNodeWithText(row).assertIsDisplayed()
        assertEquals(before,compose.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top,1f)
        compose.runOnIdle { state.value=state.value.copy(socketConnected=false,devicesCurrent=false) }
        compose.onNodeWithText(row).assertIsDisplayed()
        compose.runOnIdle { state.value=state.value.copy(socketConnected=true,devicesCurrent=true,devicePage=DevicePage()) }
        compose.onNodeWithText(row).assertIsDisplayed()
    }

    @Test fun pageActionsAndEmptySearchRemainReachable() {
        var forward: Boolean? = null
        var search: String? = null
        val state = mutableStateOf(MeshClientState(socketConnected = true, devicesCurrent = true,
            requiresSignIn = false, devicePage = DevicePage(25, 25, 2000, 25),
            devices = listOf(MeshDevice("node//one", "mesh//lab", "Alpha"))))
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(state.value, emptyList(), { _, _ -> }, {}, {}, {}, {}, {},
                onPage = { forward = it }, onServerQuery = { text, collect, ids ->
                    search = text
                    state.value = state.value.copy(deviceQuery = DeviceListQuery(text, collect, ids))
                })
        } }
        compose.onNodeWithTag("devices-page-next").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, forward) }
        compose.onNodeWithTag("devices-page-previous").performClick()
        compose.runOnIdle { assertEquals(false, forward) }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("missing")
        compose.waitUntil(5000) { search == "missing" }
        compose.runOnIdle { state.value = state.value.copy(devices = emptyList(), devicePage = DevicePage(25, 0, 0, 0, "missing")) }
        compose.onNode(hasSetTextAction()).assertExists().performTextClearance()
        compose.waitUntil(5000) { search == "" }
        compose.onNodeWithTag("devices-page-previous").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("devices-page-next").assertIsNotEnabled()
    }

    @Test fun loadingDisablesNavigationButRetainsSearch() {
        compose.setContent { MeshCentralMobileTheme {
            DevicesContent(MeshClientState(socketConnected = true, devicesCurrent = true,
                requiresSignIn = false, devicePage = DevicePage(25, 25, 2000, 25, loading = true)),
                emptyList(), { _, _ -> }, {}, {}, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithTag("devices-page-next").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("devices-page-previous").assertIsNotEnabled()
    }

    @Test fun arabicRtlAndDoubleFontKeepBothPageActionsInsideScreen() {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale("ar")); fontScale = 2f }
        val localized = compose.activity.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MeshCentralMobileTheme {
                    DevicesContent(MeshClientState(socketConnected = true, devicesCurrent = true, requiresSignIn = false,
                        devicePage = DevicePage(25, 25, 2000, 25),
                        devices = listOf(MeshDevice("node//one", "mesh//lab", "جهاز اختبار طويل الاسم"))),
                        emptyList(), { _, _ -> }, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("devices-page-next").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("devices-page-previous").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("devices-page-next", "devices-page-previous")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("Page action clipped: $tag", bounds.left >= root.left && bounds.right <= root.right)
        }
        val next = compose.onNodeWithTag("devices-page-next").fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithTag("devices-page-previous").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL navigation order", next.right <= previous.left)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "large-server-rtl-font2.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/large-server-rtl-font2.png")).use { it.readBytes() }
    }
}
