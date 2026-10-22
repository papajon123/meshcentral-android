package ru.papajon.meshcentralmobile.companion

/** Deterministic, deliberately small set of conclusions the screen is allowed to draw. */
data class CompanionDiskPressure(
    val mount: String?,
    val percent: Double,
)

data class CompanionAssessment(
    val diskHigh: List<CompanionDiskPressure>,
    val memoryHighPercent: Double?,
    val diskMeasured: Boolean,
    val memoryMeasured: Boolean,
    val truncated: Boolean,
) {
    val hasConcern: Boolean get() = diskHigh.isNotEmpty() || memoryHighPercent != null
    val hasUnknown: Boolean get() = !diskMeasured || !memoryMeasured
}

object CompanionFindings {
    const val DISK_WARNING_PERCENT = 90.0
    const val MEMORY_WARNING_PERCENT = 95.0

    /** Locale-aware percentage text without inventing a precision the server did not send. */
    fun percentText(value: Double): String {
        val whole = value.toInt()
        return if (value == whole.toDouble()) {
            whole.toString()
        } else {
            val floored = kotlin.math.floor(value * 10.0) / 10.0
            String.format(java.util.Locale.getDefault(), "%.1f", floored)
        }
    }

    /**
     * Only thresholds are evaluated. Missing values stay unknown; the app never infers a
     * cause, invents a zero, or turns an absent measurement into "healthy".
     */
    fun evaluate(diagnostics: CompanionDiagnostics): CompanionAssessment {
        val measuredDisks = diagnostics.disks.mapNotNull { disk ->
            disk.usedPercent?.let { percent -> disk to percent }
        }
        val diskHigh = measuredDisks
            .filter { (_, percent) -> percent >= DISK_WARNING_PERCENT }
            .map { (disk, percent) -> CompanionDiskPressure(disk.mount, percent) }
        val memory = diagnostics.memory.usedPercent
        return CompanionAssessment(
            diskHigh = diskHigh,
            memoryHighPercent = memory?.takeIf { it >= MEMORY_WARNING_PERCENT },
            diskMeasured = measuredDisks.isNotEmpty(),
            memoryMeasured = memory != null,
            truncated = diagnostics.truncated,
        )
    }
}
