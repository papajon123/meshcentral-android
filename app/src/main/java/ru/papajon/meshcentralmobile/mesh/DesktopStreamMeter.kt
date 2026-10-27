package ru.papajon.meshcentralmobile.mesh

/** Counts decoded framebuffer updates (KVM has no full-frame boundary) and incoming binary bytes. */
class DesktopStreamMeter {
    data class Rate(val framesPerSecond: Float, val bytesPerSecond: Long, val totalBytes: Long)
    private var bytes = 0L
    private var frames = 0L
    private var total = 0L
    private var since = 0L
    @Synchronized fun reset(now: Long) { bytes = 0; frames = 0; total = 0; since = now }
    @Synchronized fun received(count: Int) { bytes += count.coerceAtLeast(0); total += count.coerceAtLeast(0) }
    @Synchronized fun frame() { frames++ }
    @Synchronized fun sample(now: Long): Rate {
        val elapsed = (now - since).coerceAtLeast(1)
        val result = Rate(frames * 1000f / elapsed, bytes * 1000 / elapsed, total)
        bytes = 0; frames = 0; since = now
        return result
    }
}
