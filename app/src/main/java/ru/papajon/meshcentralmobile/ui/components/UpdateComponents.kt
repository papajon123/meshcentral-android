package ru.papajon.meshcentralmobile.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.update.AppUpdateViewModel
import ru.papajon.meshcentralmobile.update.UpdateCandidate
import ru.papajon.meshcentralmobile.update.UpdateResult
import ru.papajon.meshcentralmobile.update.UpdateUiState

@Composable
fun UpdateLifecycle(model: AppUpdateViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.check()
                Lifecycle.Event.ON_STOP -> model.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.check()
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
}

@Composable
fun UpdateConsentDialog(onConsent: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onConsent(false) },
        title = { Text(stringResource(R.string.update_consent_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.sm)) {
            Text(stringResource(R.string.update_consent_body))
            Text(stringResource(R.string.update_privacy_detail), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { onConsent(true) }) { Text(stringResource(R.string.update_allow)) } },
        dismissButton = { TextButton(onClick = { onConsent(false) }) { Text(stringResource(R.string.update_not_now)) } },
    )
}

@Composable
fun UpdateSettings(state: UpdateUiState, onEnabled: (Boolean) -> Unit, onCheck: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    val spacing = MeshTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        MeshCard(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = state.preferences.enabled, role = Role.Switch, onValueChange = onEnabled),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(stringResource(R.string.update_consent_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = state.preferences.enabled, onCheckedChange = null)
                }
                Text(stringResource(R.string.update_consent_body), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.update_privacy_detail), style = MaterialTheme.typography.bodySmall)
                val result = when (state.preferences.lastResult) {
                    UpdateResult.Never -> R.string.update_never
                    UpdateResult.UpToDate -> R.string.update_current
                    UpdateResult.Available -> R.string.update_found
                    UpdateResult.Suppressed -> R.string.update_suppressed
                    UpdateResult.Network -> R.string.update_network_error
                    UpdateResult.RateLimited -> R.string.update_rate_limit
                    UpdateResult.InvalidMetadata -> R.string.update_invalid_metadata
                    UpdateResult.WrongSigner -> R.string.update_wrong_signer
                }
                Text(stringResource(if (state.checking) R.string.update_checking else result))
                if (state.preferences.lastAttemptMillis > 0) {
                    Text(stringResource(R.string.update_last_check, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(state.preferences.lastAttemptMillis))), style = MaterialTheme.typography.bodySmall)
                }
                if (state.notDue) Text(stringResource(R.string.update_recently_checked), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCheck, enabled = state.preferences.enabled && !state.checking && !state.needsConsent) {
                    Text(stringResource(R.string.update_check_now))
                }
            }
        }
        state.candidate?.let { UpdateNotice(it, onLater, onSkip = onSkip) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun UpdateNotice(candidate: UpdateCandidate, onLater: () -> Unit, modifier: Modifier = Modifier, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    var browserUnavailable by remember(candidate.versionCode) { mutableStateOf(false) }
    MeshCard(modifier = modifier, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(MeshTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs)) {
            Text(stringResource(R.string.update_available, candidate.versionName), style = MaterialTheme.typography.titleSmall)
            if (browserUnavailable) Text(stringResource(R.string.update_browser_error), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.xs)) {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.releasePage)).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) { browserUnavailable = true }
                }) { Text(stringResource(R.string.update_download)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
            }
        }
    }
}
