package ru.papajon.meshcentralmobile.ui.screens.session

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import ru.papajon.meshcentralmobile.R

internal class StylusHint(val host: SnackbarHostState, val onUse: () -> Unit)

/** Claim once, before launching the Snackbar, so hover/move bursts cannot queue duplicate hints. */
@Composable
internal fun rememberStylusHint(seen: Boolean, onSeen: () -> Unit, phone: Boolean): StylusHint {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var claimed by remember { mutableStateOf(seen) }
    val currentSeen by rememberUpdatedState(seen)
    val currentOnSeen by rememberUpdatedState(onSeen)
    val message by rememberUpdatedState(stringResource(if (phone) R.string.stylus_hint_phone else R.string.stylus_hint_desktop))
    return remember(host, scope) {
        StylusHint(host) {
            if (!claimed && !currentSeen) {
                claimed = true
                currentOnSeen()
                scope.launch { host.showSnackbar(message) }
            }
        }
    }
}
