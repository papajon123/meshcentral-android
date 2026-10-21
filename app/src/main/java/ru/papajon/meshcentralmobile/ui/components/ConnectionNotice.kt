package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R

@Composable
internal fun ConnectionNotice(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    MeshCard(modifier = modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (onRetry != null) {
                    TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.connection_retry))
                    }
                }
            }
        }
    }
}
