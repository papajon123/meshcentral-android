package ru.papajon.meshcentralmobile.forward

import java.io.InputStream

/** `InputStream.readNBytes` needs Android 13; this reads up to [count] bytes or until end of stream. */
internal fun InputStream.readUpTo(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var total = 0
    while (total < count) {
        val read = read(bytes, total, count - total)
        if (read < 0) break
        total += read
    }
    return if (total == count) bytes else bytes.copyOf(total)
}
