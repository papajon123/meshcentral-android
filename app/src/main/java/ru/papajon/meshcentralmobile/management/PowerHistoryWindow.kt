package ru.papajon.meshcentralmobile.management

import java.time.Instant
import java.time.ZoneId

data class PowerInterval(val startMillis: Long, val endMillis: Long, val power: Int?)

object PowerHistoryWindow {
    const val DAY = 86_400_000L
    const val WEEK = 7 * DAY
    /** Seven local calendar dates, including today; DST days need not be 24 hours. */
    fun days(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<PowerInterval> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            PowerInterval(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), null)
        }
    }

    fun intervals(transitions: List<PowerTransition>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): List<PowerInterval> {
        val start = days(nowMillis, zone).first().startMillis
        val sorted = transitions.filter { it.timeMillis <= nowMillis }.sortedBy { it.timeMillis }
        var state = sorted.lastOrNull { it.timeMillis <= start }?.current
        var cursor = start
        return buildList {
            for (event in sorted.filter { it.timeMillis > start }) {
                if (event.timeMillis > cursor) add(PowerInterval(cursor, event.timeMillis, state))
                state = event.current
                cursor = event.timeMillis
            }
            if (cursor < nowMillis) add(PowerInterval(cursor, nowMillis, state))
        }
    }
}
