package ru.papajon.meshcentralmobile.ui.screens.management

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.management.DeviceAdmin
import ru.papajon.meshcentralmobile.management.DeviceInvitation
import ru.papajon.meshcentralmobile.management.inspectorFailureText
import ru.papajon.meshcentralmobile.localization.UiText
import ru.papajon.meshcentralmobile.localization.asString
import ru.papajon.meshcentralmobile.mesh.control.ControlRequestException
import ru.papajon.meshcentralmobile.mesh.*
import java.text.DateFormat
import java.util.Date

fun invitationShareIntent(invitation: DeviceInvitation): Intent {
    require(invitation.active())
    return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invitation.url)
}

fun invitationQr(url: String): Bitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 768, 768,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
    // QR encoding uses invariant black/white modules, independent of the app theme.
    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

internal fun clearInvitationClipboard(context: Context, invitation: DeviceInvitation?) {
    if (invitation == null || android.os.Build.VERSION.SDK_INT < 28) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return
    if (clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == invitation.url) clipboard.clearPrimaryClip()
}

@Composable
fun DeviceInviteDialog(client: MeshCentralClient, groupId: String, onDismiss: () -> Unit) {
    val state by client.clientState.collectAsState()
    val group = state.groups.firstOrNull { it.id == groupId }
    val allowed = group?.let { DeviceAdmin.canInvite(it, state) } == true
    val scope = rememberCoroutineScope()
    var invitation by remember { mutableStateOf<DeviceInvitation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(allowed, state.userId) { if (!allowed) { invitation = null; onDismiss() } }
    InviteDialogContent(group?.name.orEmpty(), invitation, busy, error, allowed, onDismiss, detail?.asString()) { hours ->
        invitation = null
        busy = true
        error = false
        detail = null
        scope.launch {
            try { invitation = client.createDeviceInvitation(groupId, hours) }
            catch (_: TimeoutCancellationException) { error = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = true; detail = inspectorFailureText(failure) }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InviteDialogContent(
    groupName: String, invitation: DeviceInvitation?, busy: Boolean, error: Boolean, allowed: Boolean,
    onDismiss: () -> Unit, detail: String? = null, onCreate: (Int) -> Unit,
) {
    var hours by remember { mutableIntStateOf(1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var copied by remember(invitation) { mutableStateOf(false) }
    val context = LocalContext.current
    val durationFormat = remember(context.resources.configuration) {
        android.icu.text.MeasureFormat.getInstance(context.resources.configuration.locales[0], android.icu.text.MeasureFormat.FormatWidth.SHORT)
    }
    var qr by remember(invitation) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(invitation) {
        if (invitation != null) qr = withContext(Dispatchers.Default) { invitationQr(invitation.url) }
    }
    LaunchedEffect(invitation) { while (invitation != null) { now = System.currentTimeMillis(); delay(1000) } }
    val active = invitation?.active(now) == true && allowed
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(invitation, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && invitation?.active() == false) clearInvitationClipboard(context, invitation)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); clearInvitationClipboard(context, invitation) }
    }
    LaunchedEffect(active, invitation) { if (!active) clearInvitationClipboard(context, invitation) }
    AlertDialog(
        modifier = Modifier.testTag("admin_invite_dialog"), onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_invite)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_invite_help, groupName))
                Text(stringResource(R.string.admin_invite_mode))
                if (invitation == null) {
                    Text(stringResource(R.string.admin_expiry))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceAdmin.inviteHours.forEach { value ->
                            FilterChip(selected = hours == value, enabled = !busy, onClick = { hours = value },
                                label = { Text(durationFormat.format(android.icu.util.Measure(value, android.icu.util.MeasureUnit.HOUR))) },
                                modifier = Modifier.testTag("admin_hours_$value"))
                        }
                    }
                } else {
                    Text(stringResource(R.string.admin_endpoint, invitation.endpointLabel), modifier = Modifier.testTag("admin_endpoint"))
                    Text(stringResource(R.string.admin_expires_at, DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT, context.resources.configuration.locales[0],
                    ).format(Date(invitation.expiresAtMillis))))
                    if (active) {
                        qr?.let { Image(it.asImageBitmap(), stringResource(R.string.admin_qr),
                            Modifier.fillMaxWidth().aspectRatio(1f).testTag("admin_qr"), filterQuality = FilterQuality.None) }
                        // URL intentionally stays out of accessibility diagnostics and saved state.
                        FlowRow {
                            TextButton(onClick = {
                                val intent = runCatching { invitationShareIntent(invitation) }.getOrNull()
                                if (intent != null) {
                                    val chooser = Intent.createChooser(intent, null)
                                    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(chooser)
                                }
                                else now = System.currentTimeMillis()
                            }, modifier = Modifier.testTag("admin_share")) {
                                Text(stringResource(R.string.admin_share))
                            }
                            TextButton(onClick = {
                                if (invitation.active()) {
                                    val clip = ClipData.newPlainText(context.getString(R.string.admin_invite), invitation.url)
                                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                    copied = true
                                }
                            }, modifier = Modifier.testTag("admin_copy")) { Text(stringResource(R.string.admin_copy)) }
                        }
                        if (copied) Text(stringResource(R.string.admin_copied))
                    } else Text(stringResource(R.string.admin_expired))
                }
                if (busy) Text(stringResource(R.string.admin_loading))
                if (error) Text(stringResource(R.string.admin_invite_failed), color = MaterialTheme.colorScheme.error)
                if (error && detail != null) Text(detail, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (!active) TextButton(onClick = { onCreate(hours) }, enabled = allowed && !busy, modifier = Modifier.testTag("admin_create")) {
                Text(stringResource(R.string.admin_create_link))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.admin_close)) } },
    )
}

private enum class AdminEditor { Metadata, Notes, Move }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceAdminActions(client: MeshCentralClient, device: MeshDevice) {
    val state by client.clientState.collectAsState()
    val current = state.socketConnected && state.devicesCurrent
    val scope = rememberCoroutineScope()
    var editor by remember(device.nodeId) { mutableStateOf<AdminEditor?>(null) }
    var originalForEdit by remember(device.nodeId) { mutableStateOf<MeshDevice?>(null) }
    var notes by remember(device.nodeId) { mutableStateOf<String?>(null) }
    var busy by remember(device.nodeId) { mutableStateOf(false) }
    var failed by remember(device.nodeId) { mutableStateOf(false) }
    var saved by remember(device.nodeId) { mutableStateOf(false) }
    var detail by remember(device.nodeId) { mutableStateOf<UiText?>(null) }
    var needsReconnect by remember(device.nodeId) { mutableStateOf(false) }
    val source = state.groups.firstOrNull { it.id == device.meshId }
    val targets = state.groups.filter { DeviceAdmin.canMove(device, source, it, current) }
    val canEdit = DeviceAdmin.canEdit(device, current)
    val canNote = DeviceAdmin.canNote(device, current)
    fun runChange(block: suspend () -> Unit) {
        busy = true; failed = false; saved = false; detail = null; needsReconnect = false
        scope.launch {
            try { block(); saved = true; editor = null }
            catch (failure: TimeoutCancellationException) { failed = true; needsReconnect = true; detail = inspectorFailureText(failure) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                failed = true; detail = inspectorFailureText(failure)
                needsReconnect = (failure as? ControlRequestException)?.code == "reconnect_required"
            }
            finally { busy = false }
        }
    }
    Column {
        FlowRow {
            if (canEdit) TextButton(enabled = !busy, onClick = { failed = false; saved = false; originalForEdit = device; editor = AdminEditor.Metadata }, modifier = Modifier.testTag("admin_edit_open")) { Text(stringResource(R.string.admin_edit)) }
            if (canNote) TextButton(enabled = !busy, onClick = {
                editor = AdminEditor.Notes; notes = null; busy = true; failed = false; saved = false; detail = null; needsReconnect = false
                scope.launch {
                    try { notes = client.readDeviceNotes(device.nodeId) }
                    catch (failure: TimeoutCancellationException) { failed = true; needsReconnect = true; detail = inspectorFailureText(failure) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        failed = true; detail = inspectorFailureText(failure)
                        needsReconnect = (failure as? ControlRequestException)?.code == "reconnect_required"
                    }
                    finally { busy = false }
                }
            }, modifier = Modifier.testTag("admin_notes_open")) { Text(stringResource(R.string.admin_notes)) }
            if (device.rights?.let { it and MeshRights.EDIT_MESH != 0 } == true) TextButton(enabled = current && !busy,
                onClick = { failed = false; saved = false; editor = AdminEditor.Move }, modifier = Modifier.testTag("admin_move_open")) { Text(stringResource(R.string.admin_move)) }
        }
        if (saved) Text(stringResource(R.string.admin_saved), modifier = Modifier.testTag("admin_saved"))
        if (failed && editor == null) Text(stringResource(R.string.admin_failed), color = MaterialTheme.colorScheme.error)
    }
    val dismiss = { if (!busy) { editor = null; notes = null } }
    val reconnect: (() -> Unit)? = if (needsReconnect) ({ editor = null; notes = null; client.retryConnection() }) else null
    when (editor) {
        AdminEditor.Metadata -> MetadataDialog(originalForEdit ?: device, busy, failed, canEdit, dismiss, detail?.asString(), reconnect) { name, description, tags ->
            runChange { client.editDevice(device.nodeId, name, description, tags, originalForEdit ?: device) }
        }
        AdminEditor.Notes -> NotesDialog(device.name, notes, busy, failed, canNote, dismiss, detail?.asString(), reconnect) { value ->
            runChange { client.saveDeviceNotes(device.nodeId, value) }
        }
        AdminEditor.Move -> MoveDialog(device, targets, busy, failed, dismiss, detail?.asString(), reconnect) { target ->
            runChange { client.moveDevice(device.nodeId, target.id) }
        }
        null -> Unit
    }
}

@Composable
internal fun MetadataDialog(device: MeshDevice, busy: Boolean, failed: Boolean, allowed: Boolean, onDismiss: () -> Unit,
    detail: String? = null, reconnect: (() -> Unit)? = null, onSave: (String, String, String) -> Unit) {
    var name by remember(device.nodeId) { mutableStateOf(device.name) }
    var description by remember(device.nodeId) { mutableStateOf(device.description) }
    var tags by remember(device.nodeId) { mutableStateOf(device.tags.joinToString(", ")) }
    var review by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    AdminDialog(stringResource(R.string.admin_edit), busy, failed, allowed, onDismiss, review,
        onConfirm = {
            if (!DeviceAdmin.validMetadataEdit(device, name, description, tags)) invalid = true
            else if (!review) review = true
            else onSave(name, description, tags)
        }, onBack = { review = false }, detail = detail, reconnect = reconnect) {
        if (review) {
            Text(stringResource(R.string.admin_edit_confirm, device.name))
            Text("${stringResource(R.string.admin_name)}: $name")
            Text("${stringResource(R.string.admin_description)}: $description")
            Text("${stringResource(R.string.admin_tags)}: $tags")
        } else {
            OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy && allowed, label = { Text(stringResource(R.string.admin_name)) }, modifier = Modifier.fillMaxWidth().testTag("admin_name"))
            OutlinedTextField(description, { description = it }, singleLine = true, enabled = !busy && allowed, label = { Text(stringResource(R.string.admin_description)) }, modifier = Modifier.fillMaxWidth().testTag("admin_description"))
            OutlinedTextField(tags, { tags = it }, singleLine = true, enabled = !busy && allowed, label = { Text(stringResource(R.string.admin_tags)) }, modifier = Modifier.fillMaxWidth().testTag("admin_tags"))
            if (invalid) Text(stringResource(R.string.admin_invalid), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun NotesDialog(name: String, original: String?, busy: Boolean, failed: Boolean, allowed: Boolean, onDismiss: () -> Unit,
    detail: String? = null, reconnect: (() -> Unit)? = null, onSave: (String) -> Unit) {
    var value by remember(original) { mutableStateOf(original.orEmpty()) }
    var review by remember { mutableStateOf(false) }
    val tooLong = value.length > 16000
    AdminDialog(stringResource(R.string.admin_notes), busy, failed, allowed,
        onDismiss, review, { if (review) onSave(value) else review = true }, { review = false }, detail, reconnect,
        canSubmit = original != null && !tooLong) {
        if (review) { Text(stringResource(R.string.admin_notes_confirm, name)); Text(value) }
        else {
            OutlinedTextField(value, { value = it }, isError = tooLong, enabled = allowed && original != null && !busy,
                label = { Text(stringResource(R.string.admin_notes)) }, modifier = Modifier.fillMaxWidth().testTag("admin_notes"))
            Text(stringResource(R.string.admin_notes_limit), color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("admin_notes_limit"))
        }
    }
}

@Composable
internal fun MoveDialog(device: MeshDevice, targets: List<MeshGroup>, busy: Boolean, failed: Boolean, onDismiss: () -> Unit,
    detail: String? = null, reconnect: (() -> Unit)? = null, onMove: (MeshGroup) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf(false) }
    val target = targets.firstOrNull { it.id == selected }
    AdminDialog(stringResource(R.string.admin_move), busy, failed, target != null, onDismiss, review,
        { if (review) target?.let(onMove) else review = true }, { review = false }, detail, reconnect) {
        if (review && target != null) Text(stringResource(R.string.admin_move_confirm, device.name, target.name))
        else {
            if (targets.isEmpty()) Text(stringResource(R.string.admin_no_destinations))
            targets.forEach { group ->
                FilterChip(selected = selected == group.id, enabled = !busy, onClick = { selected = group.id },
                    label = { Text(group.name) }, modifier = Modifier.fillMaxWidth().testTag("admin_target_${group.id}"))
            }
        }
    }
}

@Composable
private fun AdminDialog(title: String, busy: Boolean, failed: Boolean, allowed: Boolean, onDismiss: () -> Unit,
    review: Boolean, onConfirm: () -> Unit, onBack: () -> Unit, detail: String? = null, reconnect: (() -> Unit)? = null,
    canSubmit: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.imePadding().testTag("admin_dialog"),
        title = { Text(title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                if (busy) Text(stringResource(R.string.admin_loading))
                if (failed) Text(stringResource(R.string.admin_failed), color = MaterialTheme.colorScheme.error)
                if (failed && detail != null) Text(detail, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("admin_failure_detail"))
                if (failed && reconnect != null) TextButton(onClick = reconnect, enabled = !busy, modifier = Modifier.testTag("admin_reconnect")) {
                    Text(stringResource(R.string.session_options_reconnect))
                }
                if (!allowed) Text(stringResource(R.string.admin_unavailable))
            }
        }, confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && allowed && canSubmit && reconnect == null, modifier = Modifier.testTag(if (review) "admin_confirm" else "admin_review")) {
                Text(stringResource(if (review) R.string.admin_confirm else R.string.admin_review))
            }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = { if (review) onBack() else onDismiss() }, modifier = Modifier.testTag("admin_cancel")) { Text(stringResource(R.string.admin_cancel)) }
        })
}
