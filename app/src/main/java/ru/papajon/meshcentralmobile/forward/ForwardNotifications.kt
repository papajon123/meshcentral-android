package ru.papajon.meshcentralmobile.forward

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.delay

data class ForwardNotificationEntry(val name: String, val port: Int, val connections: Int = 0)

internal fun forwardNotificationEntries(states: List<PortForwardState>): List<ForwardNotificationEntry> =
    states.filter { it.running }.map { ForwardNotificationEntry(it.rule.name, it.localPort, it.connections) }.sortedBy { it.port }

/** Only visible values change the notification: traffic counters and errors do not republish it. */
internal fun Flow<List<PortForwardState>>.forwardNotifications(): Flow<List<ForwardNotificationEntry>> =
    map(::forwardNotificationEntries).distinctUntilChanged()

/** Leading update plus the latest trailing value; continuous traffic cannot starve the last count. */
internal fun Flow<List<ForwardNotificationEntry>>.coalescedForwardNotifications(): Flow<List<ForwardNotificationEntry>> =
    conflate().transform { entries ->
        emit(entries)
        delay(1000)
    }
