package ru.papajon.meshcentralmobile.discovery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.ui.screens.DevicesScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap

/** Explicit opt-in, hard-coded isolated endpoint. No production access or saved plaintext secrets. */
class LargeServerLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient()
    private var client: MeshCentralClient? = null
    private var vault: SecureVault? = null

    @After fun cleanup() {
        client?.close(); scope.cancel(); vault?.clearCredentials()
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }

    private fun helper(path: String, post: Boolean = false): JSONObject {
        val req = Request.Builder().url("http://10.0.2.2:18885/$path")
        if (post) req.post(ByteArray(0).toRequestBody())
        return http.newCall(req.build()).execute().use {
            assertEquals("Isolated helper failed", 200, it.code)
            JSONObject(it.body!!.string())
        }
    }

    private fun waitState(predicate: (MeshClientState) -> Boolean) {
        compose.waitUntil(60_000) { client?.clientState?.value?.let(predicate) == true }
        compose.waitForIdle()
    }

    @Test fun membershipBurstsUnderOnlineSearchHaveBoundedReadCounts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServer")=="4445")
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServerMode")!="unpaged")
        val bootstrap=helper("fixture")
        val secure=SecureVault(compose.activity,"review3-live");vault=secure
        val mesh=MeshCentralClient(secure,scope);client=mesh
        mesh.setAllowSelfSignedCertificates(true)
        compose.setContent { MeshCentralMobileTheme { DevicesScreen(mesh,emptyList(),{_,_->},{},{},{},{}) } }
        compose.runOnIdle { mesh.login(bootstrap.getString("serverUrl"),bootstrap.getString("username"),bootstrap.getString("password")) }
        waitState { it.devicesCurrent }
        helper("connect",true)
        compose.onNodeWithTag("device_filter_Online").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Workstation 0")
        waitState { it.devicePage.collecting && it.devicePage.filter=="workstation 0" && !it.devicePage.loading && it.devicePage.nodeIds?.size==1000 }
        try {
            helper("reset-reads",true)
            helper("burst-outside",true)
            waitState { it.devices.any { d -> d.osDescription=="Outside burst complete" } }
            assertEquals(0,helper("status").getJSONObject("reads").getInt("addressed"))
            assertEquals(0,helper("status").getJSONObject("reads").getInt("full"))
            helper("burst-members",true)
            waitState { it.devices.any { d -> d.osDescription=="Member burst complete" } }
            assertTrue(mesh.pendingMembershipChecks<=16)
            compose.waitUntil(30_000) { mesh.pendingMembershipChecks==0 }
            val reads=helper("status").getJSONObject("reads")
            assertEquals("Membership verification must not scan the list",0,reads.getInt("full"))
            assertTrue("A thousand renames must fit the verification budget",reads.getInt("addressed") in 1..16)
            assertTrue(mesh.clientState.value.devicesCurrent)
            android.util.Log.i("LargeServerReview","membership burst: outside=1000, members=1000, full=${reads.getInt("full")}, addressed=${reads.getInt("addressed")}")
        } finally { mesh.close();helper("restore-burst",true) }
    }

    @Test fun pagesSearchFiltersAndLiveEventsWithoutReconnect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServer") == "4445")
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServerMode") != "unpaged")
        val bootstrap = helper("fixture")
        assertEquals("https://10.0.2.2:4445/", bootstrap.getString("serverUrl"))
        val initialConnections = helper("status").getInt("connections")
        val secure = SecureVault(compose.activity, "large-servers-test")
        vault = secure
        val mesh = MeshCentralClient(secure, scope)
        client = mesh
        mesh.setAllowSelfSignedCertificates(true)
        val lastId = "node//" + "1999".padStart(64, '0')
        val favorites = mutableStateOf(listOf(FavoriteLink("Last workstation", lastId)))
        val showList = mutableStateOf(true)
        compose.setContent { MeshCentralMobileTheme {
            if (showList.value) DevicesScreen(mesh, favorites.value, { _, _ -> }, {}, {}, {}, {},
                recentDeviceIds = listOf(lastId), onOpenDevice = { mesh.selectDevice(it.nodeId); showList.value=false })
            else androidx.compose.material3.Button(onClick = { mesh.selectDevice(null); showList.value=true }) {
                androidx.compose.material3.Text("Return to list")
            }
        } }
        compose.runOnIdle { mesh.login(bootstrap.getString("serverUrl"), bootstrap.getString("username"), bootstrap.getString("password")) }
        waitState { it.devicesCurrent && !it.devicePage.loading && it.devicePage.totalCount == 2000 }
        assertEquals(25, mesh.clientState.value.devices.size)
        compose.onNodeWithTag("devices-page-next").performScrollTo().performClick()
        waitState { !it.devicePage.loading && it.devicePage.skip == 25 }
        assertEquals("Workstation 0025", mesh.clientState.value.devices.first().name)
        compose.onNodeWithTag("devices-page-previous").performScrollTo().performClick()
        waitState { !it.devicePage.loading && it.devicePage.skip == 0 }

        repeat(4) { index ->
            compose.runOnIdle { mesh.changeDevicePage(true) }
            waitState { !it.devicePage.loading && it.devicePage.skip == (index+1)*25 }
        }
        compose.onNodeWithText("Workstation 0100").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Return to list").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("Returning from details must preserve the fifth page",100,mesh.clientState.value.devicePage.skip)

        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Workstation 1999")
        waitState { !it.devicePage.loading && it.devicePage.filter == "workstation 1999" && it.devices.size == 1 }
        compose.onNode(hasText("Workstation 1999") and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("does-not-exist")
        waitState { !it.devicePage.loading && it.devicePage.filter == "does-not-exist" && it.devices.isEmpty() }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextClearance()
        waitState { !it.devicePage.loading && it.devicePage.filter.isEmpty() && it.devices.size == 25 }

        compose.onNodeWithTag("device_filter_Favorites").performScrollTo().performClick()
        waitState { !it.devicePage.loading && it.devicePage.requestedIds == setOf(lastId) && it.devicePage.totalCount == 1 }
        compose.onNodeWithText("Workstation 1999").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("device_filter_Recent").performScrollTo().performClick()
        waitState { !it.devicePage.loading && it.devicePage.requestedIds == setOf(lastId) }
        compose.onNodeWithText("Workstation 1999").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Workstation 1999").performTouchInput { click() }
        compose.onNodeWithText("Return to list").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("device_filter_Recent").performScrollTo().assertIsSelected()
        assertEquals(setOf(lastId),mesh.clientState.value.devicePage.requestedIds)
        compose.onNodeWithTag("device_filter_All").performScrollTo().performClick()
        waitState { !it.devicePage.loading && !it.devicePage.collecting && it.devices.size == 25 }

        helper("connect", true)
        waitState { it.devices.any { d -> d.name == "Workstation 0000" && d.online } }
        helper("rename", true)
        waitState { it.devices.any { d -> d.name == "Renamed live node" } }
        helper("disconnect", true)
        waitState { it.devices.any { d -> d.name == "Renamed live node" && !d.online } }
        helper("add", true)
        waitState { !it.devicePage.loading && it.devicePage.totalCount == 2001 && it.devices.any { d -> d.name == "Added live node" } }
        helper("remove", true)
        waitState { !it.devicePage.loading && it.devicePage.totalCount == 2000 && it.devices.none { d -> d.name == "Added live node" } }
        helper("restore", true)
        waitState { it.devices.any { d -> d.name == "Workstation 0000" } }
        assertEquals("Paging and events must keep one control connection", initialConnections + 1, helper("status").getInt("connections"))
        compose.onNodeWithTag("devices-list").performScrollToIndex(0)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "large-server-live.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/large-server-live.png")).use { it.readBytes() }

    }

    @Test fun unpagedSnapshotRefreshAndLiveEvents() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServer") == "4445")
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServerMode") == "unpaged")
        val bootstrap=helper("fixture")
        val initial=helper("status").getInt("connections")
        val secure=SecureVault(compose.activity,"large-unpaged-test"); vault=secure
        val mesh=MeshCentralClient(secure,scope); client=mesh
        mesh.setAllowSelfSignedCertificates(true)
        compose.setContent { MeshCentralMobileTheme {
            DevicesScreen(mesh,emptyList(),{_,_->},{},{},{},{})
        } }
        compose.runOnIdle { mesh.login(bootstrap.getString("serverUrl"),bootstrap.getString("username"),bootstrap.getString("password")) }
        waitState { it.devicesCurrent && it.devices.size == 2000 }
        assertFalse(mesh.clientState.value.devicePage.serverLimited)
        compose.onNodeWithTag("devices-list").performScrollToIndex(1000)
        compose.runOnIdle {
            mesh.refreshDevices()
            assertFalse(mesh.clientState.value.devicePage.loading)
            assertEquals(2000,mesh.clientState.value.devices.size)
        }
        helper("connect",true)
        waitState { it.devices.any { n -> n.name == "Workstation 0000" && n.online } }
        helper("rename",true)
        waitState { it.devices.any { n -> n.name == "Renamed live node" } }
        helper("disconnect",true)
        waitState { it.devices.any { n -> n.name == "Renamed live node" && !n.online } }
        helper("add",true)
        waitState { it.devices.size == 2001 }
        helper("remove",true)
        waitState { it.devices.size == 2000 }
        helper("restore",true)
        waitState { it.devices.any { n -> n.name == "Workstation 0000" } }
        assertEquals(initial+1,helper("status").getInt("connections"))
    }

    @Test fun collectedEventsPowerObservationAndRetainedTargetsUseTheRealServer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServer")=="4445")
        val bootstrap=helper("fixture")
        val secure=SecureVault(compose.activity,"review2-live");vault=secure
        val mesh=MeshCentralClient(secure,scope);client=mesh
        mesh.setAllowSelfSignedCertificates(true)
        val selected=mutableStateOf<String?>(null)
        compose.setContent {
            val state by mesh.clientState.collectAsState()
            MeshCentralMobileTheme {
                if(selected.value==null) DevicesScreen(mesh,emptyList(),{_,_->},{},{},{},{})
                else state.devices.firstOrNull { it.nodeId==selected.value }?.let { device ->
                    ru.papajon.meshcentralmobile.ui.screens.DeviceScreen(device,state.serverCapabilities,
                        state.socketConnected&&state.devicesCurrent,ru.papajon.meshcentralmobile.ui.DeviceTab.Overview,{},
                        {mesh.selectDevice(null);selected.value=null},{}) {}
                } ?: androidx.compose.material3.Text("Loading target")
            }
        }
        compose.runOnIdle { mesh.login(bootstrap.getString("serverUrl"),bootstrap.getString("username"),bootstrap.getString("password")) }
        waitState { it.devicesCurrent }
        val paged=mesh.clientState.value.devicePage.serverLimited
        val firstId="node//"+"0".padStart(64,'0')
        val lastId="node//"+"1999".padStart(64,'0')
        compose.runOnIdle { selected.value=lastId;mesh.selectDevice(lastId) }
        waitState { it.devices.any { d -> d.nodeId==lastId } }
        if(paged) {
            assertFalse(lastId in mesh.clientState.value.devicePage.nodeIds.orEmpty())
            val before=mesh.eventSnapshotRefreshCount
            helper("group-event",true)
            waitState { mesh.eventSnapshotRefreshCount>before && mesh.eventSnapshotRefreshIdle && !it.devicePage.loading }
        }
        helper("rename-last",true)
        // A paged server drops this event; the retained-device addressed refresh must find it.
        waitState { it.devices.any { d -> d.nodeId==lastId && d.name=="Renamed retained node" } }
        compose.onAllNodesWithText("Renamed retained node").onFirst().assertIsDisplayed()
        if(paged) assertEquals(0,mesh.clientState.value.devicePage.skip)
        helper("restore-last",true)
        compose.runOnIdle { selected.value=firstId;mesh.selectDevice(firstId) }
        waitState { it.devices.any { d -> d.nodeId==firstId } }
        helper("connect",true)
        waitState { it.devices.any { d -> d.nodeId==firstId && d.online } }
        val inspector=ru.papajon.meshcentralmobile.management.DeviceInspectorController(mesh,firstId,scope)
        try {
            helper("slow",true)
            compose.runOnIdle {
                mesh.searchDevices("",true)
                inspector.power(ru.papajon.meshcentralmobile.management.DevicePowerAction.Restart)
            }
            compose.waitUntil(10_000) { inspector.state.value.notice!=null }
            val beforeGroupRefresh=mesh.eventSnapshotRefreshCount
            helper("group-event",true)
            waitState { it.devicePage.collecting && !it.devicePage.loading && it.devices.size==2000 &&
                mesh.eventSnapshotRefreshCount>beforeGroupRefresh && mesh.eventSnapshotRefreshIdle }
            helper("fast",true)
            compose.waitUntil(20_000) {
                helper("status").getJSONObject("reads").getInt("addressed") >= if(paged)2 else 1
            }
            val fullReads=helper("status").getJSONObject("reads").getInt("full")
            if(paged) assertEquals("Power/group refresh must not restart collection",80,fullReads)
            else assertTrue("An already complete ordinary snapshot may refresh once for the group event",fullReads in 1..2)
            helper("disconnect",true)
            compose.waitUntil(10_000) { mesh.clientState.value.devices.any { it.nodeId==firstId && !it.online } }
            helper("rename",true)
            compose.waitUntil(10_000) { mesh.clientState.value.devices.any { it.nodeId==firstId && it.name=="Renamed live node" } }
            assertEquals(fullReads,helper("status").getJSONObject("reads").getInt("full"))
            helper("restore",true)
        } finally { inspector.close();helper("fast",true) }
    }

    @Test fun collectedSnapshotReceivesOffPageEventsWithoutAnyFurtherNodesRead() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeServer")=="4445")
        val bootstrap=helper("fixture")
        val secure=SecureVault(compose.activity,"review2-live-events");vault=secure
        val mesh=MeshCentralClient(secure,scope);client=mesh
        mesh.setAllowSelfSignedCertificates(true)
        compose.setContent { MeshCentralMobileTheme { DevicesScreen(mesh,emptyList(),{_,_->},{},{},{},{}) } }
        compose.runOnIdle { mesh.login(bootstrap.getString("serverUrl"),bootstrap.getString("username"),bootstrap.getString("password")) }
        waitState { it.devicesCurrent }
        val firstId="node//"+"0".padStart(64,'0')
        helper("connect",true)
        waitState { it.devices.any { d -> d.nodeId==firstId && d.online } }
        compose.runOnIdle { mesh.searchDevices("",true) }
        waitState { it.devicePage.collecting && !it.devicePage.loading && it.devices.size==2000 }
        val reads=helper("status").getJSONObject("reads")
        helper("disconnect",true)
        compose.waitUntil(8_000) { mesh.clientState.value.devices.any { it.nodeId==firstId && !it.online } }
        helper("rename",true)
        compose.waitUntil(8_000) { mesh.clientState.value.devices.any { it.nodeId==firstId && it.name=="Renamed live node" } }
        val after=helper("status").getJSONObject("reads")
        assertEquals("No full rescan can mask a broken subscription",reads.getInt("full"),after.getInt("full"))
        assertEquals("No inspector/retained read can mask a broken subscription",reads.getInt("addressed"),after.getInt("addressed"))
        helper("restore",true)
    }
}
