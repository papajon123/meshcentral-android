package ru.papajon.meshcentralmobile.ui.screens.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.notifications.NotificationController
import ru.papajon.meshcentralmobile.notifications.NotificationError
import ru.papajon.meshcentralmobile.notifications.NotificationEventKind
import ru.papajon.meshcentralmobile.notifications.NotificationFeedItem
import ru.papajon.meshcentralmobile.notifications.NotificationQuietHours
import ru.papajon.meshcentralmobile.notifications.NotificationUiState
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshSheet
import ru.papajon.meshcentralmobile.ui.components.SectionHeader
import ru.papajon.meshcentralmobile.ui.components.SheetHeader
import ru.papajon.meshcentralmobile.ui.components.StatusPill
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import androidx.core.content.ContextCompat

object NotificationSettingsTestTags {
    const val Sheet = "notification_settings_sheet"
    const val Enable = "notification_settings_enable"
    const val Disable = "notification_settings_disable"
    const val CheckNow = "notification_settings_check_now"
    const val ClearHistory = "notification_settings_clear_history"
    const val QuietStart = "notification_settings_quiet_start"
    const val QuietEnd = "notification_settings_quiet_end"
    const val Feed = "notification_settings_feed"

    fun filter(kind: NotificationEventKind) = "notification_settings_filter_${kind.wire}"
    fun feedItem(id: String) = "notification_settings_feed_$id"
}

/**
 * Opt-in, filters, quiet hours and a bounded local feed.
 *
 * No background call is made from this composable. The explicit enable button is the only opt-in,
 * and Android 13+ permission is requested immediately before [NotificationController.enable].
 */
@Composable
fun NotificationSettingsSheet(
    controller: NotificationController,
    onClose: () -> Unit,
    onOpenEvent: ((ru.papajon.meshcentralmobile.notifications.NotificationFeedItem) -> Unit)? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spacing = MeshTheme.spacing
    var clearConfirmed by remember { mutableStateOf(false) }
    var quietInvalid by rememberSaveable(controller.profileId) { mutableStateOf(false) }
    var quietStart by rememberSaveable(controller.profileId) {
        mutableStateOf(state.settings.quietHours.startHour.toString())
    }
    var quietEnd by rememberSaveable(controller.profileId) {
        mutableStateOf(state.settings.quietHours.endHour.toString())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scope.launch { controller.enable() } else controller.refresh()
    }
    val requestEnable: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { controller.enable() }
        }
    }
    fun commitQuiet(enabled: Boolean, startText: String, endText: String) {
        val start = startText.toIntOrNull()
        val end = endText.toIntOrNull()
        if (start == null || end == null || !NotificationQuietHours.isValid(start, end)) {
            quietInvalid = true
        } else {
            quietInvalid = false
            controller.updateQuietHours(enabled, start, end)
        }
    }

    LaunchedEffect(controller) { controller.refresh() }

    MeshSheet(onDismissRequest = onClose) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NotificationSettingsTestTags.Sheet)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .imePadding(),
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "header") {
                SheetHeader(
                    title = stringResource(R.string.notification_sheet_title),
                    subtitle = stringResource(R.string.notification_sheet_subtitle),
                    trailing = {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.common_action_close),
                            )
                        }
                    },
                )
            }

            item(key = "status") {
                StatusCard(
                    state = state,
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            if (!state.profileAvailable) {
                item(key = "profile-missing") {
                    CardText(
                        text = stringResource(R.string.notification_error_profile_missing),
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            }

            if (state.lastError == NotificationError.TOKEN_EXPIRED) {
                item(key = "expired") {
                    CardText(
                        text = stringResource(R.string.notification_token_expired_supporting),
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            }

            if (!state.permissionGranted) {
                item(key = "permission") {
                    CardText(
                        text = stringResource(R.string.notification_permission_denied_supporting),
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            }

            item(key = "opt-in") {
                OptInCard(
                    state = state,
                    onEnable = requestEnable,
                    onDisable = { scope.launch { controller.disable() } },
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            item(key = "filters") {
                FiltersCard(
                    state = state,
                    onToggle = { kind, checked ->
                        val kinds = if (checked) {
                            state.settings.kinds + kind
                        } else {
                            state.settings.kinds - kind
                        }
                        controller.updateFilters(kinds)
                    },
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            item(key = "quiet") {
                QuietCard(
                    state = state,
                    start = quietStart,
                    end = quietEnd,
                    invalid = quietInvalid,
                    onStartChanged = {
                        val next = it.filter(Char::isDigit).take(2)
                        quietStart = next
                        quietInvalid = false
                        commitQuiet(state.settings.quietHours.enabled, next, quietEnd)
                    },
                    onEndChanged = {
                        val next = it.filter(Char::isDigit).take(2)
                        quietEnd = next
                        quietInvalid = false
                        commitQuiet(state.settings.quietHours.enabled, quietStart, next)
                    },
                    onEnabledChanged = { enabled -> commitQuiet(enabled, quietStart, quietEnd) },
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            item(key = "check-now") {
                OutlinedButton(
                    onClick = { scope.launch { controller.checkNow() } },
                    enabled = state.enabled && !state.busy,
                    modifier = Modifier
                        .padding(horizontal = spacing.xl)
                        .fillMaxWidth()
                        .testTag(NotificationSettingsTestTags.CheckNow),
                ) {
                    Text(
                        stringResource(
                            if (state.busy) R.string.notification_checking else R.string.notification_check_now,
                        ),
                    )
                }
            }

            item(key = "explanation") {
                ExplanationCard(modifier = Modifier.padding(horizontal = spacing.xl))
            }

            item(key = "feed-header") {
                SectionHeader(
                    title = stringResource(R.string.notification_feed_title),
                    subtitle = stringResource(R.string.notification_feed_supporting),
                    trailing = {
                        if (state.feed.isNotEmpty()) {
                            TextButton(
                                onClick = { clearConfirmed = true },
                                modifier = Modifier.testTag(NotificationSettingsTestTags.ClearHistory),
                            ) {
                                Text(stringResource(R.string.notification_clear_history))
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            if (state.feed.isEmpty()) {
                item(key = "feed-empty") {
                    CardText(
                        text = stringResource(R.string.notification_feed_empty),
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            } else {
                items(state.feed.asReversed(), key = { it.id }) { item ->
                    FeedRow(
                        item = item,
                        modifier = Modifier
                            .padding(horizontal = spacing.xl)
                            .clickable(enabled = onOpenEvent != null, onClick = { onOpenEvent?.invoke(item) })
                            .testTag(NotificationSettingsTestTags.feedItem(item.id)),
                    )
                }
            }
        }
    }

    if (clearConfirmed) {
        AlertDialog(
            onDismissRequest = { clearConfirmed = false },
            title = { Text(stringResource(R.string.notification_clear_history)) },
            text = { Text(stringResource(R.string.notification_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearHistory()
                    clearConfirmed = false
                }) {
                    Text(stringResource(R.string.notification_clear_history))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmed = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(state: NotificationUiState, modifier: Modifier = Modifier) {
    MeshCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.notification_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = stringResource(
                        if (state.enabled) R.string.notification_status_enabled
                        else R.string.notification_status_disabled,
                    ),
                    color = if (state.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = state.lastCheckAt?.let {
                    stringResource(R.string.notification_last_check, formatTime(it))
                } ?: stringResource(R.string.notification_never_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.tokenExpiresAt?.let { expiresAt ->
                Text(
                    text = stringResource(R.string.notification_token_expires, formatTime(expiresAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val unsupported = state.unsupportedReason
            if (!state.backgroundSupported && unsupported != null) {
                Text(
                    text = errorText(unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else state.lastError?.let { error ->
                Text(
                    text = errorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OptInCard(
    state: NotificationUiState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MeshCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.notification_optin_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.notification_optin_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.enabled) {
                OutlinedButton(
                    onClick = onDisable,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NotificationSettingsTestTags.Disable),
                ) {
                    Text(stringResource(R.string.notification_disable_action))
                }
            } else {
                Button(
                    onClick = onEnable,
                    enabled = state.backgroundSupported && state.profileAvailable && !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NotificationSettingsTestTags.Enable),
                ) {
                    Text(
                        stringResource(
                            if (state.lastError == NotificationError.TOKEN_EXPIRED) {
                                R.string.notification_reenable_action
                            } else {
                                R.string.notification_enable_action
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FiltersCard(
    state: NotificationUiState,
    onToggle: (NotificationEventKind, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MeshCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader(
                title = stringResource(R.string.notification_filters_title),
                subtitle = stringResource(R.string.notification_filters_supporting),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            NotificationEventKind.entries.forEach { kind ->
                val checked = kind in state.settings.kinds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            role = Role.Switch,
                            onValueChange = { onToggle(kind, it) },
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag(NotificationSettingsTestTags.filter(kind)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = kindLabel(kind),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun QuietCard(
    state: NotificationUiState,
    start: String,
    end: String,
    invalid: Boolean,
    onStartChanged: (String) -> Unit,
    onEndChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MeshCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_quiet_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.notification_quiet_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.quietHours.enabled,
                    onCheckedChange = onEnabledChanged,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = start,
                    onValueChange = onStartChanged,
                    label = { Text(stringResource(R.string.notification_quiet_start)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalid,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(NotificationSettingsTestTags.QuietStart),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = onEndChanged,
                    label = { Text(stringResource(R.string.notification_quiet_end)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalid,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(NotificationSettingsTestTags.QuietEnd),
                )
            }
            if (invalid) {
                Text(
                    text = stringResource(R.string.notification_quiet_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.notification_quiet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplanationCard(modifier: Modifier = Modifier) {
    MeshCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsNone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.notification_explanation_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.notification_explanation_15min),
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.notification_explanation_os_delays),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.notification_explanation_token),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.notification_explanation_thresholds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.notification_explanation_force_stop),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedRow(item: NotificationFeedItem, modifier: Modifier = Modifier) {
    MeshCard(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = kindLabel(item.kind),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = formatTime(item.time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CardText(text: String, modifier: Modifier = Modifier) {
    MeshCard(modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun kindLabel(kind: NotificationEventKind): String = stringResource(
    when (kind) {
        NotificationEventKind.DEVICE_ONLINE -> R.string.notification_kind_device_online
        NotificationEventKind.DEVICE_OFFLINE -> R.string.notification_kind_device_offline
        NotificationEventKind.DEVICE_REQUEST_HELP -> R.string.notification_kind_request_help
        NotificationEventKind.JOB_COMPLETED -> R.string.notification_kind_job_completed
        NotificationEventKind.JOB_FAILED -> R.string.notification_kind_job_failed
        NotificationEventKind.DEVICE_ALERT -> R.string.notification_kind_device_alert
    },
)

@Composable
private fun errorText(error: NotificationError): String = stringResource(
    when (error) {
        NotificationError.NOT_ENABLED -> R.string.notification_error_not_enabled
        NotificationError.NOT_SIGNED_IN -> R.string.notification_error_not_signed_in
        NotificationError.ACCOUNT_MISMATCH -> R.string.notification_error_account_mismatch
        NotificationError.PROFILE_MISSING -> R.string.notification_error_profile_missing
        NotificationError.SELF_SIGNED_UNSUPPORTED -> R.string.notification_self_signed_unavailable
        NotificationError.INSECURE_TRANSPORT -> R.string.notification_https_required
        NotificationError.TOKEN_EXPIRED -> R.string.notification_token_expired
        NotificationError.NETWORK -> R.string.notification_error_network
        NotificationError.TLS_UNTRUSTED -> R.string.notification_error_tls
        NotificationError.HTTP -> R.string.notification_error_server
        NotificationError.RATE_LIMITED -> R.string.notification_error_rate_limited
        NotificationError.PROTOCOL -> R.string.notification_error_protocol
        NotificationError.INVALID_ENDPOINT -> R.string.notification_error_endpoint
        NotificationError.UNKNOWN -> R.string.notification_error_unknown
    },
)

private fun formatTime(time: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.SHORT,
).format(Date(time))
