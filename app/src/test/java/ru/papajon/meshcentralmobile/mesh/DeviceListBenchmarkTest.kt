package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.system.measureNanoTime

class DeviceListBenchmarkTest {
    @Test fun compareFullSortWithSingleRowInsertion() {
        val initial = (0 until 2000).map { MeshDevice("node//$it", "mesh//lab", "Workstation %04d".format(it)) }
            .sortedWith(DeviceListUpdates.comparator)
        fun run(incremental: Boolean): Pair<Long, List<MeshDevice>> {
            var state = initial
            val elapsed = measureNanoTime { repeat(500) { i ->
                val changed = initial[(i * 97) % 2000].copy(name = "Changed $i", conn = i % 2)
                state = if (incremental) DeviceListUpdates.upsert(state, changed)
                    else (state.filterNot { it.nodeId == changed.nodeId } + changed).sortedWith(DeviceListUpdates.comparator)
            } }
            return elapsed to state
        }
        repeat(2) { run(false); run(true) }
        val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
        repeat(5) {
            val old = run(false); val new = run(true)
            assertEquals(old.second, new.second)
            before.add(old.first); after.add(new.first)
        }
        println("devices=2000 events=500 rounds=5 full_sort_median_ms=${before.sorted()[2]/1e6} incremental_median_ms=${after.sorted()[2]/1e6}")
    }
}
