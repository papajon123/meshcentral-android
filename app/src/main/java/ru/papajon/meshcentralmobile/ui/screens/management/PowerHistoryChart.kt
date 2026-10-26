package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.PowerHistoryWindow
import ru.papajon.meshcentralmobile.management.PowerTransition
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun PowerHistoryChart(transitions: List<PowerTransition>, nowMillis: Long) {
    val intervals = remember(transitions, nowMillis) { PowerHistoryWindow.intervals(transitions, nowMillis) }
    val colors = MaterialTheme.colorScheme
    val on = MeshTheme.colors.online
    val sleep = MeshTheme.colors.warning
    val labels = mapOf(1 to stringResource(R.string.parity_power_on), 2 to stringResource(R.string.manage_action_sleep),
        3 to stringResource(R.string.manage_action_sleep), 4 to stringResource(R.string.manage_action_sleep),
        5 to stringResource(R.string.parity_hibernating), 6 to stringResource(R.string.parity_power_off), 8 to stringResource(R.string.parity_power_off))
    val unknown = stringResource(R.string.parity_unknown)
    Column(Modifier.fillMaxWidth().testTag("parity2_history_chart"), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        Text(stringResource(R.string.parity2_seven_days), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.parity2_history_note), style = MaterialTheme.typography.bodySmall)
        PowerHistoryWindow.days(nowMillis).forEach { day ->
            val start = day.startMillis
            val end = day.endMillis
            val duration = end - start
            val segments = intervals.filter { it.endMillis > start && it.startMillis < end }
            val totals = segments.groupBy { labels[it.power] ?: unknown }.mapValues { (_, values) ->
                values.sumOf { minOf(it.endMillis, end) - maxOf(it.startMillis, start) }
            }
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(start))
            val summary = totals.entries.joinToString(" · ") { (label, time) -> "$label ${NumberFormat.getPercentInstance().format(time.toDouble() / duration)}" }
            Text(date, style = MaterialTheme.typography.labelMedium)
            Canvas(Modifier.fillMaxWidth().height(MeshTheme.spacing.md).semantics { contentDescription = "$date: $summary" }) {
                drawRect(colors.surfaceVariant)
                segments.forEach { segment ->
                    val left = ((maxOf(segment.startMillis, start) - start).toDouble() / duration * size.width).toFloat()
                    val right = ((minOf(segment.endMillis, end) - start).toDouble() / duration * size.width).toFloat()
                    val color = when (segment.power) { 1 -> on; 2, 3, 4, 5 -> sleep; 6, 8 -> colors.outline; else -> colors.surfaceVariant }
                    drawRect(color, Offset(left, 0f), Size(right - left, size.height))
                }
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
