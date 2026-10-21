package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.AppSettings
import ru.papajon.meshcentralmobile.ui.components.IconBadge

/**
 * First-launch flow: what the app does, then the server address. Kept to one screen so a new user
 * reaches the computer list in two taps.
 */
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    onServerUrlSaved: (String) -> Unit,
    onAllowHttpInDebugChanged: (Boolean) -> Unit,
    onTrustSelfSignedChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Monitor,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FeatureRow(
            icon = Icons.Outlined.Monitor,
            title = stringResource(R.string.onboarding_feature_desktop_title),
            description = stringResource(R.string.onboarding_feature_desktop_description),
        )
        FeatureRow(
            icon = Icons.Outlined.ContentPaste,
            title = stringResource(R.string.onboarding_feature_clipboard_title),
            description = stringResource(R.string.onboarding_feature_clipboard_description),
        )
        FeatureRow(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.onboarding_feature_credentials_title),
            description = stringResource(R.string.onboarding_feature_credentials_description),
        )

        Text(
            text = stringResource(R.string.onboarding_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // The server form owns its own scrolling; give it a bounded height instead of nesting
        // two vertically scrollable containers.
        Box(modifier = Modifier.weight(1f)) {
            ServerSettingsScreen(
                settings = settings,
                onServerUrlSaved = onServerUrlSaved,
                onAllowHttpInDebugChanged = onAllowHttpInDebugChanged,
                onTrustSelfSignedChanged = onTrustSelfSignedChanged,
            )
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(
            icon = icon,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            container = MaterialTheme.colorScheme.secondaryContainer,
            size = 40.dp,
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
