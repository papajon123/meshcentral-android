package ru.papajon.meshcentralmobile.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** In-memory only. Keep SAF work across rotation; never put backup data into SavedStateHandle. */
class BackupSession : ViewModel() {
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var exportBytes by mutableStateOf<ByteArray?>(null)
    var awaitingExportPicker by mutableStateOf(false)
    var exportEncrypted by mutableStateOf(false)
    var encryptedInput by mutableStateOf<ByteArray?>(null)
    var preview by mutableStateOf<SettingsBackup?>(null)
    var applyPreferences by mutableStateOf(false)
    var skipDuplicates by mutableStateOf(true)
    fun clear() {
        exportBytes?.fill(0); encryptedInput?.fill(0)
        exportBytes = null; encryptedInput = null; preview = null
        awaitingExportPicker = false; message = null; applyPreferences = false
        skipDuplicates = true
    }
    override fun onCleared() { clear() }
}
