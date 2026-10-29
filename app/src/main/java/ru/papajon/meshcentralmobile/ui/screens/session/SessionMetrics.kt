package ru.papajon.meshcentralmobile.ui.screens.session

import java.util.Locale

/** Decimal byte units, kept in Latin script in every UI locale. */
internal fun sessionTrafficRate(bytesPerSecond: Long): String {
    var value = bytesPerSecond.coerceAtLeast(0).toDouble()
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s", "PB/s", "EB/s")
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

internal fun sessionStreamRate(fps: Float, bytesPerSecond: Long): String =
    String.format(Locale.ROOT, "%.1f fps · %s", if (fps.isFinite()) fps.coerceAtLeast(0f) else 0f, sessionTrafficRate(bytesPerSecond))
