package ru.papajon.meshcentralmobile.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

@Composable
fun BackupScreen(settings: SettingsRepository, onClose: () -> Unit, onPreferencesImported: () -> Unit, locked: Boolean = false) {
    val context = LocalContext.current
    val repository = remember(settings) { SettingsBackupRepository(settings) }
    val session: BackupSession = androidx.lifecycle.viewmodel.compose.viewModel()
    val scope = session.viewModelScope
    // Passwords and decrypted previews intentionally never enter saved-instance state.
    var includeKeys by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by session::busy
    var message by session::message
    var exportBytes by session::exportBytes
    var encryptedInput by session::encryptedInput
    var preview by session::preview
    var applyPreferences by session::applyPreferences
    var skipDuplicates by session::skipDuplicates
    fun fail(error: Exception? = null) { message = context.getString(when ((error as? BackupException)?.reason) {
        BackupFailureReason.NewerVersion -> R.string.backup_error_newer
        BackupFailureReason.PasswordOrTampered -> R.string.backup_error_auth
        BackupFailureReason.Corrupt -> R.string.backup_error_corrupt
        BackupFailureReason.Limit -> R.string.backup_error_limit
        BackupFailureReason.UnsupportedFormat -> R.string.backup_error_unsupported
        null -> R.string.backup_failed
    }) }
    fun clearPassword() { password = ""; confirmation = "" }
    fun close() { session.clear(); clearPassword(); onClose() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) clearPassword()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); clearPassword() }
    }
    LaunchedEffect(locked) { if (locked) clearPassword() }
    BackHandler(enabled = !locked && !busy) { if (preview != null || encryptedInput != null) {
        preview = null; encryptedInput = null; clearPassword()
    } else close() }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        session.awaitingExportPicker = false
        if (uri != null && bytes != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { BackupDocuments.write(context.contentResolver, uri, bytes) }
                message = context.getString(R.string.backup_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
            finally { bytes.fill(0); busy = false }
        } else bytes?.fill(0)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; message = null; clearPassword(); applyPreferences = false; skipDuplicates = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = BackupDocuments.read(context.contentResolver, uri)
                    if (BackupCrypto.isEncrypted(bytes)) {
                        BackupCrypto.requireSupportedEnvelope(bytes)
                        bytes to null
                    }
                    else null to BackupCodec.decode(bytes)
                }
                encryptedInput = result.first
                preview = result.second
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { busy = false }
        }
    }

    LaunchedEffect(exportBytes, locked) {
        if (exportBytes != null && !locked && !session.awaitingExportPicker) {
            session.awaitingExportPicker = true
            try { createDocument.launch("meshcentral-settings${if (session.exportEncrypted) "-encrypted" else ""}.json") }
            catch (_: Exception) { exportBytes?.fill(0); exportBytes = null; session.awaitingExportPicker = false; fail() }
        }
    }

    // Keep result registrations alive behind the lock, but expose no backup content/actions.
    if (locked) return

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(MeshTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md)) {
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = ::close, enabled = !busy) {
                Text(stringResource(R.string.common_action_close))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.backup_busy))
            }
            val pending = preview
            when {
                pending != null -> {
                    Text(stringResource(R.string.backup_preview), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.backup_preview_explanation))
                    val duplicateIds = repository.duplicateIds(pending)
                    pending.skippedExtensions.forEach { Text(stringResource(R.string.backup_extension_skipped, it)) }
                    pending.profiles.forEach { entry ->
                        HorizontalDivider()
                        Text(entry.profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(entry.profile.serverUrl)
                        if (entry.profile.id in duplicateIds) Text(stringResource(R.string.backup_duplicate), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.backup_profile_details, entry.favorites.size,
                            entry.profile.customShortcuts.size, entry.profile.favoriteCommands.size))
                        entry.profile.pinnedSpkiSha256?.let { Text(stringResource(R.string.backup_key_expected, it)) }
                        if (entry.loginKey != null) Text(stringResource(R.string.backup_contains_key))
                    }
                    if (duplicateIds.isNotEmpty())
                        BackupCheck(stringResource(R.string.backup_skip_duplicates), skipDuplicates, !busy) { skipDuplicates = it }
                    val values = pending.preferences
                    fun enabledLabel(value: Boolean) = context.getString(if (value) R.string.backup_value_on else R.string.backup_value_off)
                    val themeLabel = stringResource(when (values.themeMode) {
                        "Light" -> R.string.settings_theme_light
                        "Dark" -> R.string.settings_theme_dark
                        else -> R.string.settings_theme_system
                    })
                    Text(stringResource(R.string.backup_preferences_preview,
                        enabledLabel(values.blockScreenshots), values.lockTimeoutSeconds,
                        themeLabel, enabledLabel(values.dynamicColor), enabledLabel(values.stylusPrecisionEnabled),
                        enabledLabel(values.stylusHintSeen), enabledLabel(values.hideDevicesWhenLocked)))
                    BackupCheck(stringResource(R.string.backup_apply_preferences), applyPreferences, !busy) { applyPreferences = it }
                    if (!repository.canImport(pending, skipDuplicates)) Text(stringResource(R.string.backup_capacity), color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy && repository.canImport(pending, skipDuplicates), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                // Repository state changes stay on Main; crypto and parsing already finished on IO.
                                val count = repository.importConfirmed(pending, applyPreferences, skipDuplicates)
                                onPreferencesImported()
                                preview = null; encryptedInput?.fill(0); encryptedInput = null
                                message = context.getString(R.string.backup_imported, count)
                            } catch (_: Exception) { fail() }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.backup_confirm_import)) }
                    TextButton(enabled = !busy, onClick = { preview = null; encryptedInput?.fill(0); encryptedInput = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                encryptedInput != null -> {
                    Text(stringResource(R.string.backup_decrypt), style = MaterialTheme.typography.titleLarge)
                    BackupPassword(password, { password = it }, R.string.backup_password, !busy)
                    Button(enabled = !busy && password.length in 8..1024, onClick = {
                        val bytes = encryptedInput ?: return@Button
                        val pass = password.toCharArray(); clearPassword(); busy = true
                        scope.launch {
                            try {
                                preview = withContext(Dispatchers.IO) {
                                    val plain = BackupCrypto.decrypt(bytes, pass)
                                    try { BackupCodec.decode(plain, allowLoginKeys = true) } finally { plain.fill(0) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { fail(error) }
                            finally { pass.fill('\u0000'); busy = false }
                        }
                    }) { Text(stringResource(R.string.backup_decrypt)) }
                }
                else -> {
                    Text(stringResource(R.string.backup_description))
                    BackupCheck(stringResource(R.string.backup_include_keys), includeKeys, !busy) { includeKeys = it }
                    BackupPassword(password, { password = it }, R.string.backup_password, !busy)
                    Text(stringResource(R.string.backup_password_hint), style = MaterialTheme.typography.bodySmall)
                    if (password.isNotEmpty() || includeKeys) BackupPassword(confirmation, { confirmation = it }, R.string.backup_confirm_password, !busy)
                    Button(enabled = !busy, onClick = {
                        val encrypt = password.isNotEmpty() || includeKeys
                        if (encrypt && (password.length !in 8..1024 || password != confirmation)) {
                            message = context.getString(R.string.backup_password_mismatch)
                        } else {
                            val pass = password.toCharArray(); clearPassword(); busy = true; message = null
                            scope.launch {
                                try {
                                    val snapshot = repository.snapshot(includeKeys)
                                    if (snapshot.profiles.isEmpty()) message = context.getString(R.string.backup_no_profiles)
                                    else {
                                        session.exportEncrypted = encrypt
                                        exportBytes = withContext(Dispatchers.IO) {
                                            val plain = BackupCodec.encode(snapshot, includeLoginKeys = includeKeys)
                                            if (encrypt) try { BackupCrypto.encrypt(plain, pass) } finally { plain.fill(0) } else plain
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: UnsupportedExportProfile) { message = context.getString(R.string.backup_no_profiles) }
                                catch (_: Exception) { exportBytes?.fill(0); exportBytes = null; fail() }
                                finally { pass.fill('\u0000'); busy = false }
                            }
                        }
                    }) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(enabled = !busy, onClick = { openDocument.launch(arrayOf("application/json", "application/octet-stream")) }) {
                        Text(stringResource(R.string.backup_import))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BackupPassword(value: String, onValueChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value, { if (it.length <= 1024) onValueChange(it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(stringResource(label)) }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
}

@Composable
private fun BackupCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
