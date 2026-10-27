package ru.papajon.meshcentralmobile.mesh

/** Bounded, coalescing background work; UI reads use the shared wire queue ahead of this worker. */
internal class MembershipChecks(private val capacity: Int = 16) {
    class Ticket(val id: String, val name: String) { internal var valid = true }
    private val waiting = linkedMapOf<String, Ticket>()
    private var active: Ticket? = null
    @get:Synchronized var lastEventMillis: Long = 0
        private set
    @get:Synchronized val size get() = waiting.size + if (active == null) 0 else 1
    @get:Synchronized val hasWaiting get() = waiting.isNotEmpty()
    @Synchronized fun offer(id: String, name: String, now: Long): Boolean {
        lastEventMillis = now
        if (waiting[id]?.name == name || active?.let { it.id == id && it.name == name && it.valid } == true) return true
        if (active?.id == id) active?.valid = false
        if (id !in waiting && size >= capacity) return false
        waiting[id] = Ticket(id, name)
        return true
    }
    @Synchronized fun take(): Ticket? {
        if (active != null) return null
        val next = waiting.entries.firstOrNull() ?: return null
        waiting.remove(next.key)
        return next.value.also { active = it }
    }
    @Synchronized fun isCurrent(ticket: Ticket) = active === ticket && ticket.valid
    @Synchronized fun finish(ticket: Ticket) { if (active === ticket) active = null }
    @Synchronized fun observeName(id: String, name: String) {
        if (waiting[id]?.name?.let { it != name } == true) waiting.remove(id)
        active?.takeIf { it.id == id && it.name != name }?.valid = false
    }
    @Synchronized fun remove(id: String) { waiting.remove(id); if (active?.id == id) active?.valid = false }
    @Synchronized fun clear() { active?.valid = false; active = null; waiting.clear() }
}

/** Only outstanding addressed reads own an invalidation token; unrelated events allocate nothing. */
internal class DeviceReadRevisions {
    class Ticket(val id: String) { var invalidated = false; internal set }
    private val reads = mutableMapOf<String, MutableSet<Ticket>>()
    @get:Synchronized val size get() = reads.size
    @Synchronized fun begin(id: String) = Ticket(id).also { reads.getOrPut(id) { mutableSetOf() }.add(it) }
    @Synchronized fun invalidate(id: String) { reads[id]?.forEach { it.invalidated = true } }
    @Synchronized fun end(ticket: Ticket) { reads[ticket.id]?.let { it.remove(ticket); if (it.isEmpty()) reads.remove(ticket.id) } }
    @Synchronized fun clear() { reads.values.forEach { set -> set.forEach { it.invalidated = true } }; reads.clear() }
}
