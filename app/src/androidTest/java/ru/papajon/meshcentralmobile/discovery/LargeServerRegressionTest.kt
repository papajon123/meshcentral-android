package ru.papajon.meshcentralmobile.discovery

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.mesh.session.RelayProtocol
import ru.papajon.meshcentralmobile.security.SecureVault
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Regressions found by the independent large-server review. */
class LargeServerRegressionTest {
    private fun filteredFixture(f:Fixture) {
        f.limit=25;f.client.searchDevices("cafe",true)
        f.snapshot(f.nodes(),listOf("node//phone" to "Café","node//other" to "Café two"))
        await { f.client.clientState.value.devicePage.collecting && f.client.clientState.value.devices.size==2 }
    }
    private fun changed(id:String,name:String,description:String="") = JSONObject().put("action","event").put("event",
        JSONObject().put("action","changenode").put("node",JSONObject().put("_id",id).put("name",name).put("osdesc",description)))

    @Test fun eventStormIsBoundedAndUnrelatedNamesDoNotReadNodes() = fixture { f ->
        filteredFixture(f)
        repeat(1000) { f.send(changed("node//outside$it","Database $it")) }
        f.send(changed("node//phone","Café","unrelated complete"))
        await { f.client.clientState.value.devices.first { it.nodeId=="node//phone" }.osDescription=="unrelated complete" }
        assertNull(f.requests.poll(200,TimeUnit.MILLISECONDS))
        val resets=f.resetReads.get();f.autoMembership=true
        repeat(1000) { f.send(changed("node//burst$it","Café candidate $it")) }
        f.send(changed("node//phone","Café","burst complete"))
        await { f.client.clientState.value.devices.first { it.nodeId=="node//phone" }.osDescription=="burst complete" }
        assertTrue(f.client.pendingMembershipChecks<=16)
        await(30_000) { f.client.pendingMembershipChecks==0 && f.membershipReads.get()>0 }
        assertTrue("A burst must not create a thousand reads",f.membershipReads.get()<=16)
        assertEquals("limit>1 needs no reset after addressed reads",resets,f.resetReads.get())
        assertTrue(f.client.clientState.value.devicesCurrent)
        repeat(1000) { f.send(JSONObject("""{"action":"event","event":{"action":"removenode","nodeid":"node//absent$it"}}""")) }
        f.send(changed("node//phone","Café","removals complete"))
        await { f.client.clientState.value.devices.first { it.nodeId=="node//phone" }.osDescription=="removals complete" }
        assertEquals(0,f.client.trackedDeviceReads)
    }

    @Test fun lateMembershipReplyCannotReinsertANodeRenamedOutsideTheFilter() = fixture { f ->
        filteredFixture(f)
        f.send(changed("node//new","cafe-new"))
        val read=f.nodes()
        f.send(changed("node//new","Other"))
        await { f.client.clientState.value.devices.any { it.nodeId=="node//new" && it.name=="Other" } }
        f.page(read,"node//new","cafe-new")
        await { f.client.pendingMembershipChecks==0 }
        assertFalse(f.client.clientState.value.devicePage.nodeIds.orEmpty().contains("node//new"))
        assertNull(f.requests.poll(300,TimeUnit.MILLISECONDS))
    }

    @Test fun changingQueryDiscardsMembershipBacklogBeforeItCanSend() = fixture { f ->
        filteredFixture(f)
        repeat(16) { f.send(changed("node//burst$it","Café $it")) }
        val old=f.nodes();assertTrue(old.has("id"))
        f.client.searchDevices("other",true)
        f.page(old,old.getString("id"),"Café")
        val next=f.nodes()
        assertFalse("An old membership check delayed the new query",next.has("id"))
        assertEquals("other",next.getString("filter"))
        f.snapshot(next,listOf("node//other" to "Other"))
        await { f.client.clientState.value.devicePage.filter=="other" && !f.client.clientState.value.devicePage.loading }
        assertNull(f.requests.poll(1000,TimeUnit.MILLISECONDS))
    }

    @Test fun failedMembershipCheckKeepsCurrentRowsAndActionsAvailable() = fixture { f ->
        filteredFixture(f)
        f.send(changed("node//burst","Café new"))
        val read=f.nodes()
        f.send(JSONObject().put("action","nodes").put("responseid",read.getString("responseid")).put("result","Denied"))
        await { f.client.clientState.value.lastError!=null }
        assertTrue(f.client.clientState.value.devicesCurrent)
        assertEquals(2,f.client.clientState.value.devices.size)
    }

    @Test fun retainedMemberOfAFullPageDoesNotWidenItsSubscription() = fixture { f ->
        f.limit=25;f.client.refreshDevices()
        f.snapshot(f.nodes(),listOf("node//phone" to "Phone")+(1..24).map { "node//$it" to "Computer $it" })
        await { f.client.clientState.value.devicePage.returnedCount==25 }
        f.client.selectDevice("node//phone")
        assertNull("The selected page member must not be polled by ID",f.requests.poll(11,TimeUnit.SECONDS))
    }

    @Test fun addressedReplyAppliesBeforeResetAndCannotUndoLaterConnectivity() = fixture { f ->
        f.client.searchDevices("",true)
        f.page(f.nodes(),"node//phone","Phone");f.page(f.nodes(),"node//other","Other")
        await { f.client.clientState.value.devicePage.collecting }
        f.holdResets=true
        val readJob=f.scope.launch { f.client.resolveDevice("node//phone",refresh=true) }
        f.page(f.nodes(),"node//phone","Phone",conn=0)
        val reset=requireNotNull(f.resets.poll(5,TimeUnit.SECONDS))
        await { f.client.clientState.value.devices.first { it.nodeId=="node//phone" }.conn==0 }
        runBlocking { withTimeout(1000) { readJob.join() } }
        f.send(JSONObject("""{"action":"event","event":{"action":"nodeconnect","nodeid":"node//phone","conn":1,"pwr":2}}"""))
        await { f.client.clientState.value.devices.first { it.nodeId=="node//phone" }.pwr==2 }
        f.empty(reset);f.holdResets=false
        val drain=f.scope.launch { f.client.resolveDevice("node//other",refresh=true) }
        f.page(f.nodes(),"node//other","Other");runBlocking { drain.join() }
        val phone=f.client.clientState.value.devices.first { it.nodeId=="node//phone" }
        assertEquals(1,phone.conn);assertEquals(2,phone.pwr)
    }

    @Test fun oneEmptyReplyIsRetriedWithoutClosingChatOrLeakingRevisionEntries() = fixture { f ->
        f.client.refreshDevices();f.page(f.nodes(),"node//phone","Computer",agentId=1)
        await { f.client.clientState.value.devices.single().agentId==1 }
        val chat=f.client.createChat(f.client.clientState.value.devices.single())
        try {
            val lookup=f.scope.launch { f.client.resolveDevice("node//phone",refresh=true) }
            val first=f.nodes();val started=android.os.SystemClock.elapsedRealtime();f.empty(first)
            val confirm=f.nodes()
            assertTrue(android.os.SystemClock.elapsedRealtime()-started>=900)
            assertNotEquals(ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed,chat.state.value.phase)
            assertEquals(1,f.client.trackedDeviceReads)
            f.page(confirm,"node//phone","Computer",agentId=1)
            runBlocking { lookup.join() }
            assertNotEquals(ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed,chat.state.value.phase)
            assertEquals(0,f.client.trackedDeviceReads)
        } finally { chat.close() }
    }

    @Test fun emptyConfirmationCannotCrossAnAccountChange() = fixture { f ->
        var failed=false
        val lookup=f.scope.launch {
            try { f.client.resolveDevice("node//phone",refresh=true) }
            catch (_: ru.papajon.meshcentralmobile.mesh.control.ControlRequestException) { failed=true }
        }
        f.empty(f.nodes())
        Thread.sleep(150)
        f.client.logout(clearStoredCredentials=false)
        f.client.login(f.server.url("/").toString(),"other-user","synthetic-password")
        val initial=f.nodes();assertFalse(initial.has("id"))
        f.page(initial,"node//new-account","New account")
        runBlocking { withTimeout(5000) { lookup.join() } }
        assertTrue(failed);assertFalse(lookup.isCancelled)
        assertNull(f.requests.poll(300,TimeUnit.MILLISECONDS))
        assertEquals(0,f.client.trackedDeviceReads)
    }
    @Test fun chatFromFreshMainSurvivesPageChangeButClosesOnDeviceRemoval() = fixture { f ->
        f.client.refreshDevices()
        f.page(f.nodes(),"node//phone","Computer",agentId=1)
        await { f.client.clientState.value.devices.single().agentId==1 }
        val chat=f.client.createChat(f.client.clientState.value.devices.single())
        try {
            f.client.changeDevicePage(true)
            f.page(f.nodes(),"node//other","Other")
            await { f.client.clientState.value.devicePage.skip==1 }
            assertTrue(f.client.clientState.value.devices.any { it.nodeId=="node//phone" })
            assertNotEquals(ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed,chat.state.value.phase)
            // The real server suppresses an off-page removal event. Discover it by an addressed read.
            val lookup=f.scope.launch { f.client.resolveDevice("node//phone",refresh=true) }
            val read=f.nodes()
            assertEquals("node//phone",read.getString("id"))
            f.send(JSONObject().put("action","nodes").put("responseid",read.getString("responseid"))
                .put("skip",0).put("limit",1).put("nodes",JSONObject()))
            assertNotEquals("A single DB failure is not removal",ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed,chat.state.value.phase)
            val confirmation=f.nodes()
            f.empty(confirmation)
            runBlocking { lookup.join() }
            await { chat.state.value.phase==ru.papajon.meshcentralmobile.mesh.chat.ChatPhase.Closed }
        } finally { chat.close() }
    }

    @Test fun eventRefreshCannotRunInBackgroundOrAfterLeavingList() = fixture { f ->
        f.client.setDeviceListVisible(true)
        f.client.onAppBackground()
        fun rename(name:String) = f.send(JSONObject().put("action","event").put("event",JSONObject()
            .put("action","changenode").put("node",JSONObject().put("_id","node//phone").put("name",name))))
        rename("Background")
        await { f.client.clientState.value.devices.any { it.name=="Background" } }
        assertNull(f.requests.poll(500,TimeUnit.MILLISECONDS))
        f.client.onAppForeground()
        rename("Leaving")
        await { f.client.clientState.value.devices.any { it.name=="Leaving" } }
        f.client.setDeviceListVisible(false)
        assertNull(f.requests.poll(600,TimeUnit.MILLISECONDS))
    }

    @Test fun sameSearchKeepsCurrentPageAndDoesNotSendAnotherRequest() = fixture { f ->
        f.client.changeDevicePage(true)
        f.page(f.nodes(), "node//other", "Other")
        await { f.client.clientState.value.devicePage.skip == 1 }
        f.client.searchDevices("  ", false)
        assertNull(f.requests.poll(350, TimeUnit.MILLISECONDS))
        assertEquals(1, f.client.clientState.value.devicePage.skip)
    }

    @Test fun selectedDetailSurvivesSnapshotWithoutOpeningAnyRelay() = fixture { f ->
        f.client.selectDevice("node//phone")
        f.client.changeDevicePage(true)
        f.page(f.nodes(), "node//other", "Other")
        await { f.client.clientState.value.devicePage.skip == 1 }
        assertTrue(f.client.clientState.value.devices.any { it.nodeId == "node//phone" })
        assertTrue(f.client.sendPhoneCommand("node//phone", PhoneRequest(PhoneAction.Vibrate)))
    }

    @Test fun restoredSelectionFetchesMissingDeviceWithoutChangingThePageOrFilter() = fixture { f ->
        f.client.searchDevices("phone", false)
        f.page(f.nodes(),"node//phone","Phone")
        await { f.client.clientState.value.devicePage.filter=="phone" }
        f.client.selectDevice("node//outside")
        val read=f.nodes()
        assertEquals("node//outside",read.getString("id"))
        assertEquals(0,read.getInt("skip"))
        assertEquals("",read.getString("filter"))
        f.page(read,"node//outside","Outside")
        await { f.client.clientState.value.devices.any { it.nodeId=="node//outside" } }
        assertEquals(setOf("node//phone"),f.client.clientState.value.devicePage.nodeIds)
        assertEquals("phone",f.client.clientState.value.devicePage.filter)
    }

    @Test fun addressedReadOfAnotherTargetCannotFreezeSelectedPageMember() = fixture { f ->
        f.client.selectDevice("node//phone")
        val lookup=f.scope.launch { f.client.resolveDevice("node//other",refresh=true) }
        f.page(f.nodes(),"node//other","Other")
        runBlocking { lookup.join() }
        // With limit=1 the server now sends only Other's events, so no phone event is injected.
        val read=requireNotNull(f.requests.poll(15,TimeUnit.SECONDS)) { "Selected on-page device was not refreshed" }
        assertEquals("node//phone",read.getString("id"))
        f.page(read,"node//phone","Phone",conn=0)
        await { f.client.clientState.value.devices.any { it.nodeId=="node//phone" && !it.online } }
        assertEquals(setOf("node//phone"),f.client.clientState.value.devicePage.nodeIds)
    }

    @Test fun serverFilteredCollectionKeepsAccentMatchOnCoreInfoAndChecksRenamesById() = fixture { f ->
        f.client.searchDevices("cafe",true)
        f.page(f.nodes(),"node//phone","Café")
        f.page(f.nodes(),"node//other","Café two")
        await { f.client.clientState.value.devicePage.collecting && f.client.clientState.value.devices.size==2 }
        f.send(JSONObject("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//phone","name":"Café","osdesc":"Updated"}}}"""))
        await { f.client.clientState.value.devices.any { it.nodeId=="node//phone" && it.osDescription=="Updated" } }
        assertTrue("An authoritative server match must survive coreinfo", "node//phone" in f.client.clientState.value.devicePage.nodeIds.orEmpty())
        assertNull(f.requests.poll(200,TimeUnit.MILLISECONDS))
        f.send(JSONObject("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//phone","name":"Other name"}}}"""))
        val check=f.nodes()
        assertEquals("node//phone",check.getString("id"))
        assertEquals("cafe",check.getString("filter"))
        f.send(JSONObject().put("action","nodes").put("responseid",check.getString("responseid"))
            .put("skip",0).put("limit",1).put("filter","cafe").put("nodes",JSONObject()))
        f.empty(f.nodes()) // Confirm absence after the required interval, not from one DB result.
        await { "node//phone" !in f.client.clientState.value.devicePage.nodeIds.orEmpty() }
        assertEquals(1,f.client.clientState.value.devicePage.totalCount)
    }

    @Test fun removalBeforeAddressedReplyCannotResurrectTheTarget() = fixture { f ->
        var resolved: MeshDevice? = f.client.clientState.value.devices.single()
        val lookup=f.scope.launch { resolved=f.client.resolveDevice("node//phone",refresh=true) }
        val read=f.nodes()
        f.send(JSONObject("""{"action":"event","event":{"action":"removenode","nodeid":"node//phone"}}"""))
        await { f.client.clientState.value.devices.isEmpty() }
        f.page(read,"node//phone","Old phone")
        runBlocking { lookup.join() }
        assertNull(resolved)
        assertTrue(f.client.clientState.value.devices.isEmpty())
    }

    @Test fun removalCancelsPendingServerMembershipVerification() = fixture { f ->
        f.client.searchDevices("cafe",true)
        f.page(f.nodes(),"node//phone","Café")
        f.page(f.nodes(),"node//other","Café two")
        await { f.client.clientState.value.devicePage.collecting && f.client.clientState.value.devices.size==2 }
        f.send(JSONObject("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//phone","name":"Café renamed"}}}"""))
        val stale=f.nodes()
        f.send(JSONObject("""{"action":"event","event":{"action":"removenode","nodeid":"node//phone"}}"""))
        await { f.client.clientState.value.devices.none { it.nodeId=="node//phone" } }
        f.page(stale,"node//phone","Café renamed")
        val drain=f.scope.launch { f.client.resolveDevice("node//other",refresh=true) }
        f.page(f.nodes(),"node//other","Café two")
        runBlocking { drain.join() }
        assertFalse("Late matching snapshot must not undo removal", "node//phone" in f.client.clientState.value.devicePage.nodeIds.orEmpty())
        assertTrue(f.client.clientState.value.devices.none { it.nodeId=="node//phone" })
    }

    @Test fun powerObservationAndGroupRefreshDoNotRestartAnOngoingCollection() = fixture { f ->
        f.client.refreshDevices()
        f.page(f.nodes(),"node//phone","Computer",agentId=1)
        await { f.client.clientState.value.devices.single().agentId==1 }
        val inspector=ru.papajon.meshcentralmobile.management.DeviceInspectorController(f.client,"node//phone",f.scope)
        try {
            f.client.searchDevices("",true)
            val first=f.nodes()
            inspector.power(ru.papajon.meshcentralmobile.management.DevicePowerAction.Restart)
            await { inspector.state.value.notice!=null }
            Thread.sleep(2200) // The real power observer has entered its first addressed verification.
            f.send(JSONObject("""{"action":"event","event":{"action":"meshchange","meshid":"mesh//lab"}}"""))
            Thread.sleep(300)
            assertNull(f.requests.poll(100,TimeUnit.MILLISECONDS))
            f.page(first,"node//phone","Computer",agentId=1)
            val second=f.nodes()
            assertEquals(1,second.getInt("skip"))
            f.page(second,"node//other","Other",agentId=1)
            val addressed=f.nodes() // The subscription reset is automatically acknowledged by this fixture.
            assertEquals("node//phone",addressed.getString("id"))
            f.page(addressed,"node//phone","Computer",agentId=1)
            await { f.client.clientState.value.devicePage.collecting && f.client.clientState.value.devices.size==2 }
            assertNull("No full collection restart after power/event refresh",f.requests.poll(500,TimeUnit.MILLISECONDS))
        } finally { inspector.close() }
    }

    @Test fun inspectorTimeoutBeforeSendingDoesNotRestartRunningCollection() = fixture { f ->
        f.client.searchDevices("", true)
        val page = f.nodes()
        val job = f.scope.launch {
            try { f.client.requestControl(JSONObject().put("action", "nodes").put("id", "node//phone"), "nodes", timeoutMillis=150) }
            catch (_: TimeoutCancellationException) { }
        }
        runBlocking { job.join() }
        assertNull(f.requests.poll(250,TimeUnit.MILLISECONDS))
        f.page(page,"node//phone","Phone")
        val next=f.nodes()
        assertEquals(1,next.getInt("skip"))
        f.page(next,"node//other","Other")
        await { f.client.clientState.value.devices.size == 2 }
    }

    @Test fun disconnectedInspectorReadCompletesWithFailureNotCancellation() = fixture { f ->
        var failed=false
        val job=f.scope.launch {
            try { f.client.requestControl(JSONObject().put("action","nodes").put("id","node//phone"),"nodes") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ru.papajon.meshcentralmobile.mesh.control.ControlRequestException) { failed=true }
        }
        f.nodes()
        f.client.logout(clearStoredCredentials=false)
        runBlocking { job.join() }
        assertTrue(failed)
        assertFalse(job.isCancelled)
    }

    @Test fun refreshPreservesSearchQueuedBehindInspectorAndEarlierPage() = fixture { f ->
        f.client.searchDevices("old query", false)
        val oldPage = f.nodes()
        val job = f.scope.launch { f.client.requestControl(JSONObject().put("action", "nodes").put("id", "node//phone"), "nodes") }
        runBlocking { withContext(Dispatchers.Main) { } }
        f.client.searchDevices("new query", false)
        f.page(oldPage, "node//phone", "Phone")
        val inspector = f.nodes()
        assertEquals("node//phone", inspector.getString("id"))
        f.client.refreshDevices()
        f.page(inspector, "node//phone", "Phone")
        assertEquals("new query", f.nodes().getString("filter"))
        runBlocking { job.join() }
    }

    @Test fun queuedInspectorReadCannotCrossAnAccountChange() = fixture { f ->
        f.client.searchDevices("pending", false)
        f.nodes() // Deliberately leave the old page response outstanding.
        var rejected = false
        val job = f.scope.launch {
            try { f.client.requestControl(JSONObject().put("action", "nodes").put("id", "node//old-account"), "nodes") }
            catch (_: Exception) { rejected = true }
        }
        // Let the request enter its wait before changing the session generation.
        runBlocking { withContext(Dispatchers.Main) { } }
        f.client.logout(clearStoredCredentials = false)
        f.client.login(f.server.url("/").toString(), "other-user", "synthetic-password")
        val fresh = f.nodes()
        assertFalse("The old addressed read must not reach the new account", fresh.has("id"))
        f.page(fresh, "node//new-account", "New account")
        runBlocking { withTimeout(5000) { job.join() } }
        assertTrue(rejected)
        assertNull("The new session must not receive the old inspector request", f.requests.poll(350, TimeUnit.MILLISECONDS))
    }

    @Test fun collectedLocalFilterChangesOneRowWithoutReloadingAllPages() = fixture { f ->
        f.client.searchDevices("", true)
        f.page(f.nodes(), "node//phone", "Phone")
        f.page(f.nodes(), "node//other", "Other")
        await { !f.client.clientState.value.devicePage.loading && f.client.clientState.value.devices.size == 2 }
        f.send(JSONObject("""{"action":"event","event":{"action":"changenode","node":{"_id":"node//phone","name":"Renamed"}}}"""))
        await { f.client.clientState.value.devices.any { it.name == "Renamed" } }
        assertNull("A row event must not reload the collected dataset", f.requests.poll(700, TimeUnit.MILLISECONDS))
    }

    @Test fun retryUsesFailedSearchRatherThanLastSuccessfulPage() = fixture { f ->
        f.client.searchDevices("new query", false)
        val failed = f.nodes()
        f.send(JSONObject().put("action", "nodes").put("responseid", failed.getString("responseid")).put("result", "Denied"))
        await { f.client.clientState.value.lastError != null }
        f.client.refreshDevices()
        assertEquals("new query", f.nodes().getString("filter"))
    }

    @Test fun cancelInspectorDoesNotSendNextNodesUntilOldReplyArrives() = fixture { f ->
        val job = f.scope.launch { f.client.requestControl(JSONObject().put("action", "nodes").put("id", "node//phone").put("skip", 0).put("filter", ""), "nodes") }
        val old = f.nodes()
        runBlocking { job.cancelAndJoin() }
        f.client.searchDevices("new query", false)
        assertNull("Cancellation must not abandon the server request", f.requests.poll(350, TimeUnit.MILLISECONDS))
        f.page(old, "node//phone", "Phone")
        assertEquals("new query", f.nodes().getString("filter"))
    }

    @Test fun timedOutInspectorStillDrainsLateReplyBeforeAnotherNodes() = fixture { f ->
        val job = f.scope.launch { try { f.client.requestControl(JSONObject().put("action", "nodes").put("id", "node//phone").put("skip", 0).put("filter", ""), "nodes", timeoutMillis = 200) } catch (_: TimeoutCancellationException) { } }
        val old = f.nodes()
        runBlocking { job.join() }
        f.client.searchDevices("new query", false)
        assertNull("Timeout must not overlap server nodes callbacks", f.requests.poll(350, TimeUnit.MILLISECONDS))
        f.page(old, "node//phone", "Phone")
        assertEquals("new query", f.nodes().getString("filter"))
    }

    @Test fun pagingRetainsDeviceOwnedByActiveAndroidRelay() = fixture { f ->
        val phone = f.client.clientState.value.devices.single()
        val session = f.client.createRelayConnection(phone, RelayProtocol.AgentFiles)
        try {
            f.client.changeDevicePage(true)
            f.page(f.nodes(), "node//other", "Other")
            await { f.client.clientState.value.devicePage.skip == 1 && !f.client.clientState.value.devicePage.loading }
            assertTrue("Active device must survive changing the visible page", f.client.clientState.value.devices.any { it.nodeId == phone.nodeId })
            assertTrue("Phone commands remain available to the active session", f.client.sendPhoneCommand(phone.nodeId, PhoneRequest(PhoneAction.Vibrate)))
        } finally { session.close() }
    }

    private class Fixture(val client: MeshCentralClient, val scope: CoroutineScope, val requests: LinkedBlockingQueue<JSONObject>, val server: MockWebServer) {
        @Volatile var limit=1
        @Volatile var holdResets=false
        @Volatile var autoMembership=false
        val resets=LinkedBlockingQueue<JSONObject>()
        val membershipReads=java.util.concurrent.atomic.AtomicInteger()
        val resetReads=java.util.concurrent.atomic.AtomicInteger()
        lateinit var socket: WebSocket
        fun nodes(): JSONObject = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "No nodes request" }
        fun send(value: JSONObject) { assertTrue(socket.send(value.toString())) }
        fun empty(request:JSONObject) { send(JSONObject().put("action","nodes").put("responseid",request.getString("responseid"))
            .put("skip",0).put("limit",limit).put("filter",request.optString("filter")).put("nodes",JSONObject())) }
        fun snapshot(request:JSONObject, nodes:List<Pair<String,String>>) {
            send(JSONObject().put("action","nodes").put("responseid",request.getString("responseid"))
                .put("skip",request.optInt("skip")).put("filter",request.optString("filter")).put("limit",limit).put("totalcount",nodes.size)
                .put("nodes",JSONObject().put("mesh//lab",org.json.JSONArray(nodes.map { (id,name) ->
                    JSONObject().put("_id",id).put("name",name).put("conn",1).put("agent",JSONObject().put("id",1).put("caps",15)) }))))
        }
        fun page(request: JSONObject, id: String, name: String, agentId: Int = 14, conn: Int = 1) {
            val node = JSONObject().put("_id", id).put("name", name).put("conn", conn)
                .put("agent", JSONObject().put("id", agentId).put("caps", 13))
            send(JSONObject().put("action", "nodes").put("responseid", request.getString("responseid"))
                .put("skip", request.optInt("skip")).put("filter", request.optString("filter")).put("limit", limit).put("totalcount", 2)
                .put("nodes", JSONObject().put("mesh//lab", org.json.JSONArray().put(node))))
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("large-regression-$name", mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = SecureVault(isolated)
        val client = MeshCentralClient(vault, scope)
        val server = MockWebServer()
        val requests = LinkedBlockingQueue<JSONObject>()
        val f = Fixture(client, scope, requests, server)
        val response = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                f.socket = webSocket
                webSocket.send("""{"action":"userinfo","userinfo":{"_id":"user//lab"}}""")
                webSocket.send("""{"action":"meshes","meshes":[{"_id":"mesh//lab","name":"Lab","links":{"user//lab":{"rights":4294967295}}}]}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                when(message.optString("action")) {
                    "nodes" -> if (message.optString("id").startsWith("android-events-")) {
                        f.resetReads.incrementAndGet()
                        if(f.holdResets) f.resets.offer(message) else f.empty(message)
                    } else if(f.autoMembership && message.optString("id").startsWith("node//burst")) {
                        f.membershipReads.incrementAndGet()
                        f.page(message,message.getString("id"),"Café candidate")
                    } else requests.offer(message)
                    "meshes" -> if(message.has("responseid")) webSocket.send(JSONObject("""{"action":"meshes","meshes":[{"_id":"mesh//lab","name":"Lab","links":{"user//lab":{"rights":4294967295}}}]}""")
                        .put("responseid",message.getString("responseid")).toString())
                    "poweraction" -> webSocket.send(JSONObject().put("action","poweraction").put("responseid",message.getString("responseid"))
                        .put("nodeid","node//phone").put("result","ok").toString())
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        })
        server.enqueue(response)
        server.enqueue(response.clone())
        server.start()
        try {
            client.login(server.url("/").toString(), "lab", "synthetic-password")
            f.page(f.nodes(), "node//phone", "Phone")
            await { client.clientState.value.devicesCurrent && client.clientState.value.devices.size == 1 }
            block(f)
        } finally { client.close(); scope.cancel(); vault.clearCredentials(); server.shutdown() }
    }

    companion object {
        private fun await(timeoutMillis:Long=5000, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMillis
            while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue("Fixture did not reach expected state", condition())
        }
    }
}
