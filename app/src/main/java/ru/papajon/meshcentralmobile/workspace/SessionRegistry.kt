package ru.papajon.meshcentralmobile.workspace

/** Admission never evicts a live session. The caller must explicitly close one first. */
class SessionRegistry<T : AutoCloseable>(val capacity: Int = 2) {
    private val entries = linkedMapOf<String, T>()
    val size: Int get() = entries.size
    fun get(id: String): T? = entries[id]
    fun values(): List<T> = entries.values.toList()
    fun admit(id: String, create: () -> T): T? {
        entries[id]?.let { return it }
        if (entries.size >= capacity) return null
        return create().also { entries[id] = it }
    }
    fun close(id: String) { entries.remove(id)?.close() }
    fun closeAll() {
        val closing = entries.values.toList()
        entries.clear()
        closing.forEach { runCatching { it.close() } }
    }
}
