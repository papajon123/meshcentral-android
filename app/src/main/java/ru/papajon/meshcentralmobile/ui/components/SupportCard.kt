package ru.papajon.meshcentralmobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/**
 * Optional donation entry. It is hidden when the build has no support link, so a store build that may not link to
 * outside payments can switch it off with an empty `SUPPORT_URL`. Opening the page sends nothing from the app.
 */
@Composable
fun SupportCard(url: String, modifier: Modifier = Modifier) {
    if (url.isBlank()) return
    val spacing = MeshTheme.spacing
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.support_link_copied)
    MeshCard(
        modifier = modifier.fillMaxWidth().testTag("support-card"),
        shape = MaterialTheme.shapes.large,
        container = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                IconBadge(
                    icon = Icons.Outlined.VolunteerActivism,
                    container = MaterialTheme.colorScheme.tertiary,
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.support_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.support_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Button(
                onClick = {
                    runCatching { uriHandler.openUri(url) }.onFailure {
                        // No browser on the device: keep the link one paste away instead of failing silently.
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(context, copied, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("support-open"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(stringResource(R.string.support_action))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = spacing.sm).size(18.dp),
                )
            }
        }
    }
}
