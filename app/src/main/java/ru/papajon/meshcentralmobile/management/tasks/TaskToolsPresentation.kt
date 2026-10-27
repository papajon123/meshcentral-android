package ru.papajon.meshcentralmobile.management.tasks

import android.content.Context
import android.text.format.Formatter
import ru.papajon.meshcentralmobile.R
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

object TaskToolsPresentation {
    private val labels = mapOf(
        "processName" to R.string.tasks_field_process_name,
        "processUser" to R.string.tasks_field_user,
        "processDomain" to R.string.tasks_field_domain,
        "cmd" to R.string.tasks_field_command,
        "path" to R.string.tasks_field_path,
        "userName" to R.string.tasks_field_user,
        "startTime" to R.string.tasks_field_start_time,
        "mainWindowTitle" to R.string.tasks_field_window_title,
        "machineName" to R.string.tasks_field_machine,
        "privateMemorySize" to R.string.tasks_field_private_memory,
        "virtualMemorySize" to R.string.tasks_field_virtual_memory,
        "workingSet" to R.string.tasks_field_working_set,
        "peakWorkingSet" to R.string.tasks_field_peak_working_set,
        "peakVirtualMemorySize" to R.string.tasks_field_peak_virtual_memory,
        "peakPagedMemorySize" to R.string.tasks_field_peak_paged_memory,
        "pagedSystemMemorySize" to R.string.tasks_field_paged_system_memory,
        "pagedMemorySize" to R.string.tasks_field_paged_memory,
        "nonpagedSystemMemorySize" to R.string.tasks_field_nonpaged_memory,
        "totalProcessorTime" to R.string.tasks_field_cpu_total,
        "userProcessorTime" to R.string.tasks_field_cpu_user,
        "privilegedProcessorTime" to R.string.tasks_field_cpu_kernel,
        "sessionId" to R.string.tasks_field_session,
        "handleCount" to R.string.tasks_field_handles,
        "PriorityBoostEnabled" to R.string.tasks_field_priority_boost
    )
    fun startup(context: Context, value: String?): String = context.getString(when (value?.uppercase(Locale.ROOT)) {
        "AUTO_START", "AUTO", "AUTOMATIC" -> R.string.tasks_start_auto
        "DEMAND_START", "MANUAL" -> R.string.tasks_start_manual
        "DISABLED" -> R.string.tasks_start_disabled
        "BOOT_START", "BOOT" -> R.string.tasks_start_boot
        "SYSTEM_START", "SYSTEM" -> R.string.tasks_start_system
        else -> R.string.tasks_unknown
    })
    fun details(context: Context, fields: Map<String, String>): List<Pair<String, String>> {
        val locale = context.resources.configuration.locales[0]
        val numbers = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        val unknown = context.getString(R.string.tasks_unknown)
        return labels.mapNotNull { (key, label) ->
            val raw = fields[key] ?: return@mapNotNull null
            val formatted = when {
                key.contains("Memory") || key in setOf("workingSet", "peakWorkingSet") ->
                    raw.toLongOrNull()?.takeIf { it >= 0 }?.let { Formatter.formatFileSize(context, it) } ?: unknown
                key.endsWith("ProcessorTime") -> raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?.let { context.getString(R.string.tasks_seconds, numbers.format(it)) } ?: unknown
                key == "startTime" -> runCatching {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale).format(Date.from(Instant.parse(raw)))
                }.getOrDefault(unknown)
                key in setOf("sessionId", "handleCount") -> raw.toLongOrNull()?.let(numbers::format) ?: unknown
                key == "PriorityBoostEnabled" -> context.getString(when (raw.lowercase(Locale.ROOT)) {
                    "true" -> R.string.tasks_yes; "false" -> R.string.tasks_no; else -> R.string.tasks_unknown
                })
                else -> raw
            }
            context.getString(label) to formatted
        }
    }
}
