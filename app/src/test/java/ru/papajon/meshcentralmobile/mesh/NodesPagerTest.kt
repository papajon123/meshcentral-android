package ru.papajon.meshcentralmobile.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NodesPagerTest {
    @Test fun identicalRefreshDuringCollectionDoesNotRestartItsFirstPage() {
        val pager = NodesPager()
        val first = pager.request("", true)!!
        assertNull(pager.request("", true))
        val next = pager.accept(reply(first, listOf("a"), 2, 1)).next!!
        assertEquals(1, next.getInt("skip"))
        assertNull(pager.request("", true))
        val restored = pager.accept(reply(next, listOf("b"), 2, 1))
        assertTrue(restored.next!!.has("id"))
        val result = pager.accept(reply(restored.next, emptyList(), null, 1))
        assertEquals(listOf("a", "b"), result.devices!!.map { it.nodeId })
    }

    @Test fun collectionRestoresGlobalEventsEvenWithLimitOneAndEmptyResult() {
        val pager = NodesPager()
        val first = pager.request("", true)!!
        val reset = pager.accept(reply(first, emptyList(), null, 1))
        assertNull(reset.devices)
        assertEquals(0, reset.next!!.getInt("skip"))
        assertEquals("", reset.next.getString("filter"))
        assertTrue(reset.next.getString("id").isNotEmpty())
        assertTrue(pager.pending)
        assertTrue(pager.accept(reply(reset.next, emptyList(), null, 1)).devices!!.isEmpty())
        assertFalse(pager.pending)
    }
    private fun device(id: String) = MeshDevice(nodeId = id, meshId = "mesh//lab", name = id)
    private fun reply(request: JSONObject, ids: List<String>, total: Int? = 60, limit: Int? = 25) =
        MeshControlEvent.Nodes(ids.map(::device), request.getString("responseid"), request.getInt("skip"), limit, total, request.optString("filter"))

    @Test fun requestAndParserPreservePagingFieldsAndExplicitClear() {
        val request = MeshControlMessages.nodes("r", 25, 25, "")
        assertEquals("", request.getString("filter"))
        assertEquals(25, request.getInt("skip"))
        val parsed = MeshControlParser.parse("""{"action":"nodes","responseid":"r","skip":25,"limit":25,"totalcount":2000,"filter":"abc","nodes":{"mesh//lab":[{"_id":"node//1","name":"ABC"}]}}""") as MeshControlEvent.Nodes
        assertEquals(2000, parsed.totalCount)
        assertEquals(25, parsed.skip)
        assertEquals(25, parsed.limit)
        assertEquals("r", parsed.responseId)
        assertEquals("abc", parsed.filter)
        assertEquals("mesh//lab", parsed.devices.single().meshId)
    }

    @Test fun pageReplacesPreviousRowsAndKeepsTotal() {
        val pager = NodesPager()
        val first = pager.request("", false)!!
        val result = pager.accept(reply(first, (0..24).map(Int::toString)))
        assertTrue(result.page!!.hasNext)
        val second = pager.request("", false, 25)!!
        val next = pager.accept(reply(second, (25..49).map(Int::toString)))
        assertEquals("25", next.devices!!.first().nodeId)
        assertEquals(25, next.devices.size)
        assertEquals(60, next.page!!.totalCount)
        assertTrue(next.page.hasPrevious)
    }

    @Test fun newerSearchWaitsForOldReplyThenDiscardsIt() {
        val pager = NodesPager()
        val first = pager.request("old", false)!!
        assertNull(pager.request(" NEW ", false))
        val stale = pager.accept(reply(first, listOf("old")))
        assertNull(stale.devices)
        assertEquals("new", stale.next!!.getString("filter"))
        val result = pager.accept(reply(stale.next, listOf("new"), total = null))
        assertEquals("new", result.devices!!.single().name)
        assertEquals(1, result.page!!.totalCount)
    }

    @Test fun unknownResponseCannotReplaceCurrentPageOrClearFlight() {
        val pager = NodesPager()
        val request = pager.request("", false)!!
        assertNull(pager.accept(reply(request, listOf("wrong")).copy(responseId = "other")).devices)
        assertTrue(pager.pending)
        assertEquals("right", pager.accept(reply(request, listOf("right"))).devices!!.single().name)
    }

    @Test fun localFiltersCollectPagesAndDeduplicateIds() {
        val pager = NodesPager()
        val first = pager.request("", true)!!
        val one = pager.accept(reply(first, listOf("a", "b"), 4, 2))
        assertNull(one.devices)
        val reset = pager.accept(reply(one.next!!, listOf("b", "c"), 4, 2))
        val two = pager.accept(reply(reset.next!!, emptyList(), null, 2))
        assertEquals(listOf("a", "b", "c"), two.devices!!.map { it.nodeId })
        assertTrue(two.page!!.collecting)
        assertFalse(two.page.hasNext)
    }

    @Test fun missingCountFullPageAllowsNextButShortPageStops() {
        val pager = NodesPager()
        val first = pager.request("", false)!!
        val one = pager.accept(reply(first, listOf("a", "b"), null, 2))
        assertNull(one.page!!.totalCount)
        assertTrue(one.page.hasNext)
        val next = pager.request("", false, 2)!!
        val two = pager.accept(reply(next, listOf("c"), null, 2))
        assertEquals(3, two.page!!.totalCount)
        assertFalse(two.page.hasNext)
    }

    @Test fun deletedLastPageBacksUpAndErrorsKeepOldData() {
        val pager = NodesPager()
        val request = pager.request("", false, 25)!!
        val result = pager.accept(reply(request, emptyList(), 25))
        assertEquals(0, result.next!!.getInt("skip"))
        val failed = pager.accept(reply(result.next, emptyList()).copy(result = "Denied"))
        assertTrue(failed.failed)
        assertNull(failed.devices)
    }

    @Test fun unpagedServerReturnsAllWithoutInventingAPageLimit() {
        val pager = NodesPager()
        val request = pager.request("", false)!!
        val result = pager.accept(reply(request, listOf("a", "b"), null, null))
        assertFalse(result.page!!.serverLimited)
        assertFalse(result.page.hasNext)
        assertEquals(2, result.page.totalCount)
    }

    @Test fun unpagedServerDoesNotApplyAnOffsetRestoredFromAnOlderLimitedSession() {
        val pager=NodesPager();val request=pager.request("",false,100)!!
        val result=pager.accept(reply(request,listOf("a","b"),null,null).copy(skip=null))
        assertEquals(0,result.page!!.skip);assertEquals(2,result.page.totalCount)
    }

    @Test fun eventUpdatesPreserveOtherInstancesAndSortedOrder() {
        val nodes = (0..1999).map { device("Node %04d".format(it)) }.sortedWith(DeviceListUpdates.comparator)
        val updated = nodes[1500].copy(name = "AAA", conn = 1)
        val result = DeviceListUpdates.upsert(nodes, updated)
        assertEquals(2000, result.size)
        assertEquals(updated, result.first())
        assertSame(nodes[10], result[11])
        assertEquals(result.sortedWith(DeviceListUpdates.comparator), result)
    }

    @Test fun allFourLiveEventsParseWithoutSnapshots() {
        val frames = listOf(
            """{"action":"nodeconnect","nodeid":"node//1","conn":1,"pwr":1}""",
            """{"action":"changenode","node":{"_id":"node//1","name":"New"}}""",
            """{"action":"addnode","node":{"_id":"node//2","meshid":"mesh//lab","name":"Added"}}""",
            """{"action":"removenode","nodeid":"node//2"}""")
        val events = frames.map { (MeshControlParser.parse("""{"action":"event","event":$it}""") as MeshControlEvent.NodeUpdated).event }
        assertTrue(events[0] is MeshNodeEvent.NodeConnected)
        assertTrue(events[1] is MeshNodeEvent.NodeChanged)
        assertTrue(events[2] is MeshNodeEvent.NodeAdded)
        assertTrue(events[3] is MeshNodeEvent.NodeRemoved)
    }
}
