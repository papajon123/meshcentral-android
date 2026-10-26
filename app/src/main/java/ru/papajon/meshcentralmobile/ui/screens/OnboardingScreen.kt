package ru.papajon.meshcentralmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

/**
 * First-launch flow: a short, confident introduction followed by the real server form. Both live in
 * the single scroll container owned by [ServerSettingsScreen], so a large font, the IME or a short
 * landscape window never strands the Save action.
 */
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    onServerUrlSaved: (String) -> Unit,
    onAllowHttpInDebugChanged: (Boolean) -> Unit,
    onTrustSelfSignedChanged: (Boolean) -> Unit,
    initialLoginKey: String = "",
    onLoginKeySaved: ((String, String) -> Unit)? = null,
) {
    ServerSettingsScreen(
        settings = settings,
        onServerUrlSaved = onServerUrlSaved,
        onAllowHttpInDebugChanged = onAllowHttpInDebugChanged,
        onTrustSelfSignedChanged = onTrustSelfSignedChanged,
        initialLoginKey = initialLoginKey,
        onLoginKeySaved = onLoginKeySaved,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
        introductoryContent = { OnboardingIntroduction() },
    )
}

@Composable
private fun OnboardingIntroduction() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MeshTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.Monitor,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
