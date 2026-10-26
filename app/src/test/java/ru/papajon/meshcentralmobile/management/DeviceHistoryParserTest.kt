package ru.papajon.meshcentralmobile.management

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshControlMessages

class DeviceHistoryParserTest {
    @Test fun requestsUseNodeIdAndFiftyEventLimit() {
        assertTrue(JSONObject("""{"action":"events","nodeid":"node//example","limit":50}""").similar(MeshControlMessages.deviceEvents("node//example")))
        assertTrue(JSONObject("""{"action":"powertimeline","nodeid":"node//example"}""").similar(MeshControlMessages.powerTimeline("node//example")))
    }
    @Test fun eventDatesAndMissingFieldsAreTolerant() {
        val events = DeviceHistoryParser.events(JSONObject("""{"events":[{"time":"2026-10-25T00:00:00Z","action":"changenode","msg":"Changed"},{"time":"bad"},null]}"""))
        assertEquals(2, events.size)
        assertEquals(1792886400000L, events[0].timeMillis)
        assertEquals("Changed", events[0].message)
        assertNull(events[1].timeMillis); assertNull(events[1].message)
    }
    @Test fun eventListAndTextAreBoundedAndEmptyIsDistinctFromMalformed() {
        val array = JSONArray().apply { repeat(70) { put(JSONObject().put("msg", "x".repeat(2000))) } }
        val events = DeviceHistoryParser.events(JSONObject().put("events", array))
        assertEquals(50, events.size); assertEquals(1024, events[0].message!!.length)
        assertTrue(DeviceHistoryParser.events(JSONObject("""{"events":[]}""")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { DeviceHistoryParser.events(JSONObject()) }
    }
    @Test fun timelineDecodesSecondsAndCumulativeDeltasNewestFirst() {
        val events = DeviceHistoryParser.timeline(JSONObject("""{"timeline":[0,1700000000,1,120,4,60,1]}"""))
        assertEquals(listOf(PowerTransition(1700000180000, 4, 1), PowerTransition(1700000120000, 1, 4), PowerTransition(1700000000000, 0, 1)), events)
    }
    @Test fun normalizesNoRecordsFallbackMilliseconds() {
        assertTrue(DeviceHistoryParser.timeline(JSONObject("""{"timeline":[1,1700000000000,1]}""")).isEmpty())
    }
    @Test fun rejectsBrokenTimelinesAndPreservesUnknownPower() {
        for (raw in listOf("[]", "[1]", "[1,2]", "[1,2,3,4]", "[1,-1,3]", "[1,1700000000,3,-1,4]", "[1,1e30,3]")) {
            val reply = JSONObject("""{"timeline":$raw}""")
            if (raw == "[]") assertTrue(DeviceHistoryParser.timeline(reply).isEmpty())
            else assertThrows(IllegalArgumentException::class.java) { DeviceHistoryParser.timeline(reply) }
        }
        assertNull(DeviceHistoryParser.timeline(JSONObject("""{"timeline":[1,1700000000,99]}""")).single().current)
    }
    @Test fun onlyLatestHundredTransitionsAreRetained() {
        val array = JSONArray().put(0).put(1700000000).put(1)
        repeat(120) { array.put(60).put(if (it % 2 == 0) 4 else 1) }
        val events = DeviceHistoryParser.timeline(JSONObject().put("timeline", array))
        assertEquals(100, events.size)
        assertEquals(1700007200000L, events.first().timeMillis)
    }
}
