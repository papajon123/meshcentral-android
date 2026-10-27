package ru.papajon.meshcentralmobile.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException

class DeviceListPolicyTest {
    @Test fun serverCollationMembershipSurvivesCoreInfoUntilServerRechecksIt() {
        val page=DevicePage(limit=25,collecting=true,filter="cafe",nodeIds=setOf("node//cafe"),totalCount=1,returnedCount=1)
        val updated=DeviceListPolicy.snapshotMembership(page,MeshDevice("node//cafe","mesh//lab","Café"))
        assertEquals(setOf("node//cafe"),updated.nodeIds)
        assertEquals(1,updated.totalCount)
    }
    @Test fun userInfoDoesNotDuplicateAnInitialSnapshotThatAlreadyArrived() {
        assertFalse(DeviceListPolicy.needsInitialSnapshot(pending=true,current=false))
        assertFalse(DeviceListPolicy.needsInitialSnapshot(pending=false,current=true))
        assertTrue(DeviceListPolicy.needsInitialSnapshot(pending=false,current=false))
    }
    @Test fun moveEventRetainsIdentityForSelectedDeviceRightsRefresh() {
        val parsed=MeshControlParser.parse("""{"action":"event","event":{"action":"nodemeshchange","newMeshId":"mesh//new","node":{"_id":"node//selected"}}}""") as MeshControlEvent.NodeUpdated
        val move=parsed.event as MeshNodeEvent.MeshChanged
        assertEquals("node//selected",move.nodeId)
        assertEquals("mesh//new",move.meshId)
    }
    private val fifth = DevicePage(limit = 25, skip = 100, totalCount = 2000, returnedCount = 25, filter = "desk")
    private fun node(id: String, name: String = id) = MeshDevice(id, "mesh//lab", name)

    @Test fun returningToOldQuerySupersedesInFlightNewQuery() {
        val pager=NodesPager()
        fun reply(r:JSONObject,name:String)=MeshControlEvent.Nodes(listOf(node(name,name)),r.getString("responseid"),0,25,1,r.getString("filter"))
        val a=pager.request("alpha",false)!!
        pager.accept(reply(a,"alpha"))
        val b=pager.request("beta",false)!!
        assertTrue(DeviceListPolicy.queryChanged(DeviceListQuery("alpha"),DeviceListQuery("beta")))
        assertNull(pager.request("alpha",false))
        val stale=pager.accept(reply(b,"beta"))
        assertNull(stale.devices)
        assertEquals("alpha",stale.next!!.getString("filter"))
        assertEquals("alpha",pager.accept(reply(stale.next,"alpha")).devices!!.single().name)
    }

    @Test fun equivalentNormalizedQueriesDoNotRequestADifferentBrowserIntent() {
        assertFalse(DeviceListPolicy.queryChanged(DeviceListQuery(" DESK "), fifth.query))
        val collected = fifth.copy(skip = 0, collecting = true, returnedCount = 2000)
        assertFalse(DeviceListPolicy.queryChanged(DeviceListQuery("desk", true), collected.query))
        assertTrue(DeviceListPolicy.queryChanged(DeviceListQuery("new"), fifth.query))
    }

    @Test fun ordinaryRefreshAndReconnectKeepRowsButPageOrFilterChangeMayHideThem() {
        val ordinary = DevicePage(returnedCount = 2000)
        assertFalse(DeviceListPolicy.hideRows(ordinary, DeviceListQuery(), 0, false))
        assertFalse(DeviceListPolicy.hideRows(fifth, fifth.query, 100, false))
        assertTrue(DeviceListPolicy.hideRows(fifth, fifth.query, 125, false))
        assertTrue(DeviceListPolicy.hideRows(fifth, DeviceListQuery("new"), 0, false))
        assertFalse(DeviceListPolicy.hideRows(fifth, DeviceListQuery("new"), 0, true))
    }

    @Test fun pollingRequiresVisibleForegroundConnectedIdleActualPage() {
        assertTrue(DeviceListPolicy.poll(fifth, true, true, true, false, false))
        for (flags in listOf(listOf(false,true,true,false,false), listOf(true,false,true,false,false),
            listOf(true,true,false,false,false), listOf(true,true,true,true,false), listOf(true,true,true,false,true))) {
            assertFalse(DeviceListPolicy.poll(fifth, flags[0],flags[1],flags[2],flags[3],flags[4]))
            assertFalse(DeviceListPolicy.reconcile(fifth, flags[0],flags[1],flags[2],flags[3],flags[4]))
        }
        for (page in listOf(DevicePage(), fifth.copy(collecting=true), fifth.copy(localSearch=true),
            fifth.copy(requestedIds=setOf("node//fav")), fifth.copy(skip=0,returnedCount=1,totalCount=1))) {
            assertFalse(DeviceListPolicy.poll(page,true,true,true,false,false))
        }
    }

    @Test fun unrelatedCoreInfoAndRemovalDoNotRefreshShortFilteredPage() {
        val existing = node("node//own", "Desk one")
        val page = fifth.copy(skip=0,returnedCount=1,totalCount=1,nodeIds=setOf(existing.nodeId))
        assertFalse(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeChanged(node("node//foreign", "Laptop")), null))
        assertFalse(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeRemoved("node//foreign"), null))
        assertFalse(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeChanged(existing.copy(agentVersion="2")), existing))
        assertTrue(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeChanged(existing.copy(name="Laptop")), existing))
        assertTrue(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeChanged(node("node//foreign", "New desk")), null))
        assertTrue(DeviceListPolicy.refreshForEvent(page, MeshNodeEvent.NodeAdded(node("node//new")), null))
    }

    @Test fun selectedDeviceSurvivesPageShiftAndUnpinsWhenClosed() {
        val selected = node("node//selected")
        val next = node("node//next")
        val result = DeviceListPolicy.retainSnapshot(listOf(next), listOf(selected), emptySet(), selected.nodeId, true, true)
        assertTrue(result.contains(selected))
        assertEquals(listOf(next), DeviceListPolicy.retainSnapshot(listOf(next),result,emptySet(),null,true,true))
        assertEquals(listOf(next), DeviceListPolicy.retainSnapshot(listOf(next),result,emptySet(),selected.nodeId,false,true))
    }

    @Test fun androidInputIsNeverCarriedAcrossReconnectOrOnOrdinaryServer() {
        val old = node("node//phone").copy(agentId=14,conn=1,remoteInput=PhoneRemoteInput.Enabled)
        val fresh = old.copy(remoteInput=PhoneRemoteInput.Unknown)
        fun merge(limited:Boolean,current:Boolean) = DeviceListPolicy.retainSnapshot(listOf(fresh),listOf(old),emptySet(),null,limited,current).single()
        assertEquals(PhoneRemoteInput.Enabled,merge(true,true).remoteInput)
        assertEquals(PhoneRemoteInput.Unknown,merge(true,false).remoteInput)
        assertEquals(PhoneRemoteInput.Unknown,merge(false,true).remoteInput)
    }

    @Test fun queuedIntentRearmsTimeoutWithoutHidingErrorAndOnlyExplicitRetryReconnects() {
        val watch = NodesWatchdog()
        val first = watch.arm()
        assertTrue(watch.expire(first))
        val second = watch.arm()
        assertTrue(watch.timedOut)
        assertFalse(watch.mayReconnect(false))
        assertTrue(watch.mayReconnect(true))
        assertFalse(watch.expire(first))
        assertTrue(watch.expire(second))
        watch.complete()
        assertFalse(watch.timedOut)
        assertFalse(watch.expire(second))
    }

    @Test fun transportFailureReachesInspectorAsUnknownRatherThanCancellingItsLoop() = runBlocking {
        val pending = PendingNodesRead("test")
        var observations = 0
        val observer = launch {
            try { pending.answer.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ControlRequestException) { assertEquals("disconnected",error.code); observations++ }
            observations++ // The observation loop can complete and publish its final Unknown status.
        }
        pending.disconnected()
        observer.join()
        assertEquals(2,observations)
        assertFalse(observer.isCancelled)
    }

    @Test fun absentFilterEchoCollectsAndSearchesBeyondFirstPageWithHonestCount() {
        val pager=NodesPager()
        val first=pager.request("needle",false)!!
        fun reply(req:JSONObject,devices:List<MeshDevice>) = MeshControlEvent.Nodes(devices,
            req.getString("responseid"),req.getInt("skip"),2,3,filter=null)
        val one=pager.accept(reply(first,listOf(node("a"),node("b"))))
        assertNull(one.devices)
        val second=one.next!!
        assertEquals(2,second.getInt("skip"))
        assertEquals("",second.getString("filter"))
        val reset=pager.accept(reply(second,listOf(node("c","Needle"))))
        val result=pager.accept(reply(reset.next!!,emptyList()))
        assertEquals(listOf("c"),result.devices!!.map{it.nodeId})
        assertEquals(1,result.page!!.totalCount)
        assertTrue(result.page.localSearch)
        assertFalse(result.page.hasNext)
        assertEquals(DeviceListQuery("needle"),result.page.query)
    }

    @Test fun favoriteIdsAreAddressedAndUnknownIdsDoNotExpandToFullScan() {
        val pager=NodesPager()
        pager.configureLimit(25)
        val first=pager.request("",false,ids=setOf("node//a","node//z"))!!
        assertEquals("node//a",first.getString("id"))
        assertEquals(25,first.getInt("limit"))
        val one=pager.accept(MeshControlEvent.Nodes(listOf(node("node//a")),first.getString("responseid"),0,25))
        val second=one.next!!
        assertEquals("node//z",second.getString("id"))
        val reset=pager.accept(MeshControlEvent.Nodes(emptyList(),second.getString("responseid"),0,25))
        val result=pager.accept(MeshControlEvent.Nodes(emptyList(),reset.next!!.getString("responseid"),0,25))
        assertEquals(1,result.devices!!.size)
        assertEquals(1,result.page!!.totalCount)
        assertFalse(result.page.pagingActive)
        assertNull(pager.request("",false,ids=emptySet()))
        assertEquals(0,pager.emptyResult()!!.page!!.totalCount)
    }
}
