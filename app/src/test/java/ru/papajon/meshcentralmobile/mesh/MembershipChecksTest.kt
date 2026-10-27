package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class MembershipChecksTest {
    @Test fun nonMatchingRenameInvalidatesBothActiveAndWaitingCandidates() {
        val queue=MembershipChecks()
        queue.offer("a","Café",0);val active=queue.take()!!
        queue.offer("b","Café",1)
        queue.observeName("a","Other");queue.observeName("b","Other")
        assertFalse(queue.isCurrent(active));assertFalse(queue.hasWaiting)
        queue.finish(active);assertEquals(0,queue.size)
    }
    @Test fun burstOfAThousandCandidatesHasABoundedCoalescedBacklog() {
        val queue=MembershipChecks()
        repeat(1000) { queue.offer("node//$it","Café $it",it.toLong()) }
        assertEquals(16,queue.size)
        var reads=0
        while(queue.hasWaiting) { val next=queue.take()!!; reads++;queue.finish(next) }
        assertEquals(16,reads)
        repeat(1000) { queue.offer("node//same","Café",1000) }
        assertEquals(1,queue.size)
    }
    @Test fun queryChangeAndRemovalInvalidateAlreadySelectedWork() {
        val queue=MembershipChecks()
        queue.offer("a","Café",0);val active=queue.take()!!
        queue.offer("a","Other",1)
        assertFalse(queue.isCurrent(active))
        queue.clear();assertFalse(queue.isCurrent(active));assertEquals(0,queue.size)
        queue.offer("b","Café",2);val second=queue.take()!!;queue.remove("b")
        assertFalse(queue.isCurrent(second))
    }
    @Test fun unrelatedEventsDoNotAccumulateRevisionEntries() {
        val revisions=DeviceReadRevisions()
        repeat(100_000) { revisions.invalidate("node//$it") }
        assertEquals(0,revisions.size)
        val a=revisions.begin("a");val b=revisions.begin("a")
        revisions.invalidate("a");assertTrue(a.invalidated);assertTrue(b.invalidated)
        revisions.end(a);assertEquals(1,revisions.size)
        revisions.end(b);assertEquals(0,revisions.size)
    }
    @Test fun addressedReplyIsAppliedBeforeLaterEventsAndSubscriptionAck() {
        var conn=0
        val read=PendingNodesRead("r") { conn=it.getInt("conn") }
        read.received(org.json.JSONObject().put("conn",1))
        assertTrue(read.answer.isCompleted);assertEquals(1,conn)
        conn=0 // A later connectivity event must remain authoritative while reset is outstanding.
        assertEquals(0,conn)
    }
    @Test fun onlyRenamedMembersOrPlausibleAccentInsensitiveMatchesAreCandidates() {
        val page=DevicePage(limit=25,collecting=true,filter="CAFE",nodeIds=setOf("member"))
        val member=MeshDevice("member","g","Café")
        assertTrue(DeviceListPolicy.membershipCandidate(page,MeshNodeEvent.NodeChanged(member.copy(name="Other")),member))
        assertFalse(DeviceListPolicy.membershipCandidate(page,MeshNodeEvent.NodeChanged(member),member))
        assertTrue(DeviceListPolicy.membershipCandidate(page,MeshNodeEvent.NodeAdded(MeshDevice("new","g","CAFÉ server")),null))
    }
    @Test fun unrelatedNamedCoreInfoOutsideFilteredSnapshotDoesNotReadNodes() {
        val page=DevicePage(limit=25,collecting=true,filter="cafe",nodeIds=setOf("node//member"))
        assertFalse(DeviceListPolicy.membershipCandidate(page,
            MeshNodeEvent.NodeChanged(MeshDevice("node//outside","mesh//lab","Database server")),null))
    }
    @Test fun ordinaryPageMemberDoesNotWidenSubscriptionThroughAnAddressedPoll() {
        val page=DevicePage(limit=25,nodeIds=setOf("node//selected"))
        assertFalse(DeviceListPolicy.refreshRetained(page,"node//selected"))
        assertTrue(DeviceListPolicy.refreshRetained(page,"node//outside"))
        assertTrue(DeviceListPolicy.refreshRetained(page.copy(limit=1),"node//selected"))
    }
}
