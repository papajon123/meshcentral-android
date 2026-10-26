package ru.papajon.meshcentralmobile.management

import java.util.Locale
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.ResolverStyle

/** A BIOS release date is a calendar date, not an instant to shift to another time zone. */
object BiosDate {
    private val cim = Regex("[0-9]{14}\\.[0-9]{6}[+-][0-9]{3}")
    private val timestamp = DateTimeFormatter.ofPattern("uuuuMMddHHmmss.SSSSSS", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun format(value: String?, locale: Locale = Locale.getDefault()): String? {
        if (value == null || !cim.matches(value)) return value
        return runCatching {
            LocalDateTime.parse(value.substring(0, 21), timestamp).toLocalDate()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }.getOrDefault(value)
    }
}
