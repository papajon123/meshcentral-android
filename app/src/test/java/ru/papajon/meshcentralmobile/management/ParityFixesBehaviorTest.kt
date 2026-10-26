package ru.papajon.meshcentralmobile.management

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.control.ControlReplyPolicy

class ParityFixesBehaviorTest {
    @Test fun wakeFailuresKeepTheReasonAndZeroRelayCountIsNotSuccess() {
        for (reason in listOf("No known MAC addresses for this device", "No network information for this device", "Invalid nodeid", "Used 0 device(s) to send wake packets")) {
            assertEquals(reason, ControlReplyPolicy.failure("wakedevices", JSONObject().put("result", reason)))
        }
        assertNull(ControlReplyPolicy.failure("wakedevices", JSONObject().put("result", "Used 2 device(s) to send wake packets")))
    }
    @Test fun offlineSilenceIsEmptyButOnlineTimeoutAndTransportFailureAreErrors() = runBlocking {
        val silent: suspend () -> JSONObject = { withTimeout(1) { delay(100); JSONObject() } }
        assertTrue(DeviceHistoryLoader.powerTimeline(silent) { true }.isEmpty())
        assertTrue(runCatching { DeviceHistoryLoader.powerTimeline(silent) { false } }.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(runCatching { DeviceHistoryLoader.powerTimeline({ throw java.io.IOException() }) { true } }.exceptionOrNull() is java.io.IOException)
    }
    @Test fun currentStateFallbackDoesNotInventATransition() {
        assertTrue(DeviceHistoryParser.timeline(JSONObject("""{"action":"powertimeline","nodeid":"node//example","timeline":[1,1793000000000,1],"tag":"example"}""")).isEmpty())
    }
    @Test fun historyRowsStartAtLocalMidnightAndIncludeTodayAcrossDst() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        val days = PowerHistoryWindow.days(now, zone)
        assertEquals(7, days.size)
        assertEquals(Instant.parse("2026-10-18T22:00:00Z").toEpochMilli(), days.first().startMillis)
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), days.last().startMillis)
        assertEquals(25 * 3600_000L, days.last().endMillis - days.last().startMillis)
        assertEquals("2026-10-25", Instant.ofEpochMilli(days.last().startMillis).atZone(zone).toLocalDate().toString())
        assertEquals(days.first().startMillis, PowerHistoryWindow.intervals(emptyList(), now, zone).first().startMillis)
    }
    @Test fun previewCannotSendOnDoubleTapAndExplicitSendIsOneShot() {
        val sent = mutableListOf<String>()
        val review = ToastReview("Title", "Message", 1000)
        assertFalse(review.send(1000) { _, text -> sent.add(text) })
        assertFalse(review.send(1300) { _, text -> sent.add(text) })
        assertTrue(sent.isEmpty())
        assertTrue(review.send(2000) { _, text -> sent.add(text) })
        assertFalse(review.send(2001) { _, text -> sent.add(text) })
        assertEquals(listOf("Message"), sent)
    }
}
