package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.model.AppDestination

/** Quiet title hierarchy; the actual status and actions belong to the screen's state. */
@Composable
fun MeshScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions?.invoke()
    }
}

/** All destinations keep visible labels; Material owns selection, ripple and accessibility. */
@Composable
fun MeshNavigationBar(current: AppDestination, onSelect: (AppDestination) -> Unit, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    NavigationBar(
        modifier = modifier.fillMaxWidth().heightIn(min = if (fontScale > 1.2f) (80 * fontScale).dp else 80.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        AppDestination.entries.forEach { destination ->
            val label = appDestinationLabel(destination)
            NavigationBarItem(
                modifier = Modifier.semantics { contentDescription = label },
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon(), contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = { Text(appDestinationLabel(destination), softWrap = true) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/** Same identity/order as the compact bar; width changes never create a second navigation state. */
@Composable
fun MeshNavigationRail(current: AppDestination, onSelect: (AppDestination) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier = modifier.widthIn(min = 104.dp, max = 168.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = { Spacer(Modifier.height(12.dp)); Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(20.dp)) },
    ) {
        AppDestination.entries.forEach { destination ->
            val label = appDestinationLabel(destination)
            NavigationRailItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon(), contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = { Text(appDestinationLabel(destination), softWrap = true) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = label },
            )
        }
    }
}

private fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.Devices -> Icons.Outlined.DesktopWindows
    AppDestination.Session -> Icons.Outlined.Monitor
    AppDestination.Settings -> Icons.Outlined.Settings
}
