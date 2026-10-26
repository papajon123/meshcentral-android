package ru.papajon.meshcentralmobile.management

import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

object DeviceHistoryLoader {
    /** 1.3.1 sends no reply when both retained events and current connectivity are absent. */
    suspend fun powerTimeline(request: suspend () -> JSONObject, freshOffline: () -> Boolean): List<PowerTransition> = try {
        DeviceHistoryParser.timeline(request(), limit = 10_000)
    } catch (timeout: TimeoutCancellationException) {
        if (freshOffline()) emptyList() else throw timeout
    }
}
