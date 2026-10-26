package ru.papajon.meshcentralmobile.ui.screens.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import java.text.DateFormat
import java.util.Date
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DeviceHistoryEvent
import ru.papajon.meshcentralmobile.management.PowerTransition
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

@Composable
internal fun DeviceHistoryContent(
    events: List<DeviceHistoryEvent>?, timeline: List<PowerTransition>?, eventsError: Boolean, timelineError: Boolean,
    loading: Boolean, statusContent: (@Composable () -> Unit)?, modifier: Modifier,
) {
    val now = remember(timeline) { System.currentTimeMillis() }
    LazyColumn(modifier.testTag(DeviceInspectorTestTags.ContentList),
        contentPadding = PaddingValues(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
        if (statusContent != null) item { statusContent() }
        if (!timeline.isNullOrEmpty() && !timelineError) item { PowerHistoryChart(timeline, now) }
        item { Text(stringResource(R.string.parity_events), style = MaterialTheme.typography.titleMedium) }
        item { HistoryStatus(events?.isEmpty(), eventsError, loading) }
        items(events.orEmpty()) { event ->
            Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs)) {
                Text(formatHistoryTime(event.timeMillis), style = MaterialTheme.typography.labelMedium)
                Text(event.message ?: event.action ?: stringResource(R.string.parity_unknown))
                HorizontalDivider()
            }
        }
        item { Text(stringResource(R.string.parity_power_history), style = MaterialTheme.typography.titleMedium) }
        item { HistoryStatus(timeline?.isEmpty(), timelineError, loading) }
        items(timeline.orEmpty().take(100)) { transition ->
            Column {
                Text(formatHistoryTime(transition.timeMillis), style = MaterialTheme.typography.labelMedium)
                Text("${powerLabel(transition.previous)} → ${powerLabel(transition.current)}")
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryStatus(empty: Boolean?, error: Boolean, loading: Boolean) {
    when {
        error -> Text(stringResource(R.string.parity_history_error), color = MaterialTheme.colorScheme.error)
        empty == true -> Text(stringResource(R.string.parity_history_empty))
        empty == null -> Text(stringResource(if (loading) R.string.manage_loading else R.string.parity_unknown))
    }
}

@Composable
private fun formatHistoryTime(time: Long?): String = time?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: stringResource(R.string.parity_unknown)

@Composable
private fun powerLabel(power: Int?): String = when (power) {
    1 -> stringResource(R.string.parity_power_on)
    2, 3, 4 -> stringResource(R.string.manage_action_sleep) + " (S${power - 1})"
    5 -> stringResource(R.string.parity_hibernating)
    6, 8 -> stringResource(R.string.parity_power_off)
    else -> stringResource(R.string.parity_unknown)
}
