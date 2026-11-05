package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.papajon.meshcentralmobile.R

/** Readable without a browser, network connection, JavaScript, or a MeshCentral account. */
@Composable
fun PlayPrivacyPolicyButton() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("play-privacy-policy")) {
        Text(stringResource(R.string.play_privacy_policy))
    }
    if (!open) return
    val assets = LocalContext.current.assets
    val language = if (LocalLocale.current.platformLocale.language == "ru") "ru" else "en"
    var body by remember(language) { mutableStateOf<String?>(null) }
    var failed by remember(language) { mutableStateOf(false) }
    LaunchedEffect(language) {
        try {
            body = withContext(Dispatchers.IO) {
                assets.open("privacy.$language.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        modifier = Modifier.testTag("play-privacy-dialog"),
        title = { Text(stringResource(R.string.play_privacy_policy)) },
        text = {
            when {
                failed -> Text(stringResource(R.string.play_privacy_unavailable))
                body == null -> CircularProgressIndicator()
                else -> SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(body.orEmpty(), modifier = Modifier.testTag("play-privacy-content"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}
