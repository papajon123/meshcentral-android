package ru.papajon.meshcentralmobile.discovery

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.MeshSessionViewModel
import ru.papajon.meshcentralmobile.ui.screens.DevicesScreen
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** A fresh client plus Android saved-state restoration; no surviving socket is used as proof. */
class LargeServerRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun server(onRead:(JSONObject)->Boolean={false}): MockWebServer = MockWebServer().apply {
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    socket.send("""{"action":"userinfo","userinfo":{"_id":"user//restore"}}""")
                    socket.send("""{"action":"meshes","meshes":[{"_id":"mesh//lab","name":"Lab","links":{"user//restore":{"rights":4294967295}}}]}""")
                }
                override fun onMessage(socket: WebSocket, text: String) {
                    val req=JSONObject(text)
                    if(req.optString("action")!="nodes") return
                    if(onRead(req)) return
                    val all=listOf("node//first" to "First", "node//last" to "Remote computer")
                    val matches=all.filter { (req.optString("id").isEmpty() || it.first==req.getString("id")) &&
                        it.second.lowercase().contains(req.optString("filter")) }
                    val skip=req.optInt("skip")
                    val nodes=matches.drop(skip).take(1).map { (id,name) -> JSONObject().put("_id",id).put("name",name)
                        .put("conn",1).put("agent",JSONObject().put("id",1).put("caps",15)) }
                    socket.send(JSONObject().put("action","nodes").put("responseid",req.getString("responseid"))
                        .put("skip",skip).put("limit",1).put("totalcount",matches.size).put("filter",req.optString("filter"))
                        .put("nodes",JSONObject().put("mesh//lab",JSONArray(nodes))).toString())
                }
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(1000,null) }
            })
        }
        start()
    }

    @Test fun restoredFilterAndSearchPopulateFreshClientAndSurviveOpeningDetails() {
        val server=server()
        val clients=mutableListOf<MeshCentralClient>()
        val scopes=mutableListOf<CoroutineScope>()
        val vault=SecureVault(compose.activity,"review2-restoration")
        val restoration=StateRestorationTester(compose)
        try {
            restoration.setContent {
                val entry=remember { CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).let { scope ->
                    scopes.add(scope); MeshCentralClient(vault,scope).also { clients.add(it) }
                } }
                DisposableEffect(entry) { onDispose { entry.close() } }
                LaunchedEffect(entry) { entry.login(server.url("/").toString(),"restore","synthetic-password") }
                var list by rememberSaveable { mutableStateOf(true) }
                MeshCentralMobileTheme {
                    if(list) DevicesScreen(entry,listOf(FavoriteLink("Remote","node//last")),{_,_->},{},{},{},{},
                        onOpenDevice={entry.selectDevice(it.nodeId);list=false})
                    else Button(onClick={entry.selectDevice(null);list=true}) { Text("Return to list") }
                }
            }
            compose.waitUntil(10_000) { clients.lastOrNull()?.clientState?.value?.devicesCurrent==true }
            compose.onNodeWithTag("device_filter_Favorites").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Remote")
            compose.waitUntil(10_000) { clients.last().clientState.value.deviceQuery==DeviceListQuery("remote",ids=setOf("node//last")) &&
                !clients.last().clientState.value.devicePage.loading }
            val old=clients.last()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { clients.last()!==old && clients.last().clientState.value.devicesCurrent &&
                clients.last().clientState.value.deviceBrowserFilter==DeviceBrowserFilter.Favorites &&
                clients.last().clientState.value.devicePage.requestedIds==setOf("node//last") }
            assertEquals("Remote",clients.last().clientState.value.deviceBrowserSearch)
            compose.onNodeWithText("Remote computer").performScrollTo().performClick()
            compose.onNodeWithText("Return to list").performClick()
            compose.onNodeWithTag("device_filter_Favorites").performScrollTo().assertIsSelected()
            assertEquals(DeviceBrowserFilter.Favorites,clients.last().clientState.value.deviceBrowserFilter)
            assertEquals(DeviceListQuery("remote",ids=setOf("node//last")),clients.last().clientState.value.deviceQuery)
        } finally { clients.forEach { it.close() }; scopes.forEach { it.cancel() }; vault.clearCredentials(); server.shutdown() }
    }

    @Test fun savedCardSelectionResolvesOutsideFreshFirstPage() {
        val server=server()
        val app=compose.activity.application as Application
        val settings=SettingsRepository(app)
        val previous=settings.activeProfile.id
        val profile=settings.createProfile("Restored card fixture",server.url("/").toString(),true)
        val workspace=ConnectionWorkspace.get(app)
        try {
            compose.runOnIdle {
                settings.activateProfile(profile.id); settings.updateAllowHttpInDebug(true); workspace.switchProfile(profile.id)
                workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
            assertFalse(workspace.client.clientState.value.devices.any { it.nodeId=="node//last" })
            val saved=SavedStateHandle(mapOf("identity" to workspace.identityFor(server.url("/").toString(),"user//restore"),
                "node" to "node//last","tab" to "Overview"))
            compose.runOnIdle { MeshSessionViewModel(app,saved).bindIdentity(server.url("/").toString(),"user//restore") }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devices.any { it.nodeId=="node//last" } }
            assertEquals("node//last",workspace.selectedDeviceId)
            assertEquals(setOf("node//first"),workspace.client.clientState.value.devicePage.nodeIds)
            assertEquals(0,workspace.client.clientState.value.devicePage.skip)
        } finally {
            compose.runOnIdle { workspace.closeAll(); workspace.credentialVault.clearCredentials();
                settings.activateProfile(previous);workspace.switchProfile(previous);settings.removeProfile(profile.id) }
            server.shutdown()
        }
    }

    @Test fun savedOpenCardRestoresBrowserQueryIntoANewClientBeforeBack() {
        val server=server();val app=compose.activity.application
        val settings=SettingsRepository(app);val previous=settings.activeProfile.id
        val profile=settings.createProfile("Browser restoration",server.url("/").toString(),true)
        val workspace=ConnectionWorkspace.get(app)
        try {
            val saved=SavedStateHandle()
            lateinit var first:MeshSessionViewModel
            compose.runOnIdle {
                settings.activateProfile(profile.id);settings.updateAllowHttpInDebug(true);workspace.switchProfile(profile.id)
                workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
            compose.runOnIdle {
                first=MeshSessionViewModel(app,saved);first.bindIdentity(server.url("/").toString(),"user//restore")
                workspace.client.setDeviceBrowserFilter(DeviceBrowserFilter.Online)
                workspace.client.setDeviceBrowserSearch("Remote")
                workspace.client.searchDevices("remote",true)
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicePage.collecting && !workspace.client.clientState.value.devicePage.loading }
            compose.runOnIdle { first.openDevice(workspace.client.clientState.value.devices.first { it.nodeId=="node//last" }) }
            val restored=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            val old=workspace.client
            compose.runOnIdle {
                settings.activateProfile(previous);workspace.switchProfile(previous)
                settings.activateProfile(profile.id);workspace.switchProfile(profile.id)
                workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
            assertNotSame(old,workspace.client)
            lateinit var fresh:MeshSessionViewModel
            compose.runOnIdle { fresh=MeshSessionViewModel(app,restored);fresh.bindIdentity(server.url("/").toString(),"user//restore") }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicePage.filter=="remote" && !workspace.client.clientState.value.devicePage.loading }
            assertEquals(DeviceListQuery("remote",true),workspace.client.clientState.value.deviceQuery)
            compose.runOnIdle { fresh.browseDevices() }
            compose.setContent { MeshCentralMobileTheme { DevicesScreen(workspace.client,emptyList(),{_,_->},{},{},{},{}) } }
            compose.onNodeWithTag("device_filter_Online").performScrollTo().assertIsSelected()
            compose.onNode(hasSetTextAction()).assertTextContains("Remote")
        } finally {
            compose.runOnIdle { workspace.closeAll();workspace.credentialVault.clearCredentials();settings.activateProfile(previous);workspace.switchProfile(previous);settings.removeProfile(profile.id) }
            server.shutdown()
        }
    }

    @Test fun repeatedCardRestorationKeepsTheRequestedPageWhileItsReplyIsPending() {
        val server=server();val app=compose.activity.application
        val settings=SettingsRepository(app);val previous=settings.activeProfile.id
        val profile=settings.createProfile("Page restoration",server.url("/").toString(),true)
        val workspace=ConnectionWorkspace.get(app)
        var saved=SavedStateHandle(mapOf("node" to "node//last","tab" to "Overview","browserFilter" to "All","browserSearch" to "",
            "queryFilter" to "","queryCollect" to false,"querySkip" to 1))
        try {
            repeat(2) {
                compose.runOnIdle {
                    settings.activateProfile(profile.id);settings.updateAllowHttpInDebug(true);workspace.switchProfile(profile.id)
                    workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
                }
                compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
                if (it==0) saved["identity"]=workspace.identityFor(server.url("/").toString(),"user//restore")
                compose.runOnIdle { MeshSessionViewModel(app,saved).bindIdentity(server.url("/").toString(),"user//restore") }
                assertEquals(1,saved.get<Int>("querySkip"))
                compose.waitUntil(10_000) { workspace.client.clientState.value.devicePage.skip==1 && !workspace.client.clientState.value.devicePage.loading }
                saved=SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                compose.runOnIdle { workspace.credentialVault.clearCredentials();settings.activateProfile(previous);workspace.switchProfile(previous) }
            }
        } finally {
            compose.runOnIdle { workspace.closeAll();settings.activateProfile(previous);workspace.switchProfile(previous);settings.removeProfile(profile.id) }
            server.shutdown()
        }
    }

    @Test fun timedOutNotificationOpensItsTargetInsteadOfRemainingPending() {
        withNotificationFixture(holdCollection=true) { workspace,_,_ ->
            compose.runOnIdle { workspace.client.searchDevices("hold",true) }
            compose.runOnIdle { workspace.eventDestination=ru.papajon.meshcentralmobile.workspace.EventDestination(workspace.profileId,"node//last",null) }
            compose.waitUntil(22_000) { workspace.selectedDeviceId=="node//last" && workspace.eventDestination==null }
        }
    }

    @Test fun missingCardRetryDoesNotRestartACurrentBrowserSnapshot() {
        withNotificationFixture(holdCollection=false) { workspace,reads,_ ->
            compose.runOnIdle { workspace.eventDestination=ru.papajon.meshcentralmobile.workspace.EventDestination(workspace.profileId,"node//missing",null) }
            compose.waitUntil(10_000) { workspace.selectedDeviceId=="node//missing" && workspace.eventDestination==null &&
                workspace.client.clientState.value.resolvingDeviceIds.isEmpty() }
            val full=reads.count { !it.has("id") };val addressed=reads.count { it.optString("id")=="node//missing" }
            val retry=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                .getString(ru.papajon.meshcentralmobile.R.string.connection_retry)
            compose.onNodeWithText(retry).performClick()
            compose.waitUntil(10_000) { reads.count { it.optString("id")=="node//missing" }>=addressed+2 &&
                workspace.client.clientState.value.resolvingDeviceIds.isEmpty() }
            assertEquals(full,reads.count { !it.has("id") })
        }
    }

    private fun withNotificationFixture(holdCollection:Boolean,block:(ConnectionWorkspace,java.util.concurrent.ConcurrentLinkedQueue<JSONObject>,MockWebServer)->Unit) {
        val reads=java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val server=server { req -> reads.add(req); holdCollection && !req.has("id") && req.optString("filter")=="hold" }
        val app=compose.activity.application;val settings=SettingsRepository(app);val previous=settings.activeProfile.id
        val profile=settings.createProfile("Notification outcome",server.url("/").toString(),true)
        val workspace=ConnectionWorkspace.get(app)
        val updates=ru.papajon.meshcentralmobile.update.UpdatePreferencesStore(app);val previousUpdates=updates.load()
        try {
            updates.save(previousUpdates.copy(enabled=false,consentVersionCode=1))
            compose.runOnIdle {
                settings.activateProfile(profile.id);settings.updateAllowHttpInDebug(true);workspace.switchProfile(profile.id)
                workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
            androidx.test.core.app.ActivityScenario.launch(ru.papajon.meshcentralmobile.MainActivity::class.java).use {
                block(workspace,reads,server)
            }
        } finally {
            compose.runOnIdle { workspace.eventDestination=null;workspace.closeAll();workspace.credentialVault.clearCredentials();
                settings.activateProfile(previous);workspace.switchProfile(previous);settings.removeProfile(profile.id) }
            updates.save(previousUpdates);server.shutdown()
        }
    }

    @Test fun chatNotificationResolvesAnOffPageTargetWithoutCreatingAConversation() {
        val server=server()
        val app=compose.activity.application as Application
        val settings=SettingsRepository(app)
        val previous=settings.activeProfile.id
        val profile=settings.createProfile("Notification target fixture",server.url("/").toString(),true)
        val workspace=ConnectionWorkspace.get(app)
        try {
            compose.runOnIdle {
                settings.activateProfile(profile.id);settings.updateAllowHttpInDebug(true);workspace.switchProfile(profile.id)
                workspace.client.login(server.url("/").toString(),"restore","synthetic-password")
            }
            compose.waitUntil(10_000) { workspace.client.clientState.value.devicesCurrent }
            assertFalse(workspace.client.clientState.value.devices.any { it.nodeId=="node//last" })
            val intent=android.content.Intent(app,ru.papajon.meshcentralmobile.MainActivity::class.java)
                .putExtra(ru.papajon.meshcentralmobile.notifications.NotificationContract.EXTRA_PROFILE_ID,profile.id)
                .putExtra(ru.papajon.meshcentralmobile.notifications.NotificationContract.EXTRA_NODE_ID,"node//last")
                .putExtra(ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier.EXTRA_SESSION,"node//last|Chat")
            androidx.test.core.app.ActivityScenario.launch<ru.papajon.meshcentralmobile.MainActivity>(intent).use {
                compose.waitUntil(15_000) { workspace.selectedDeviceId=="node//last" && workspace.eventDestination==null }
                assertTrue(workspace.client.clientState.value.devices.any { it.nodeId=="node//last" })
                assertTrue("A notification must not start a new chat",workspace.sessions.none { it.chat!=null })
            }
        } finally {
            compose.runOnIdle { workspace.eventDestination=null;workspace.closeAll();workspace.credentialVault.clearCredentials();
                settings.activateProfile(previous);workspace.switchProfile(previous);settings.removeProfile(profile.id) }
            server.shutdown()
        }
    }
}
