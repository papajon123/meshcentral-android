package ru.papajon.meshcentralmobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.localization.AppLanguage
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme

private const val PICKER_TAG = "app-language-picker"
private const val SYSTEM_OPTION_TAG = "app-language-option-system"
private const val OPTION_TAG_PREFIX = "app-language-option-"

/**
 * One row with the current app language. The dialog lists the system default and every locale from
 * `locales_config.xml` under its own endonym; picking one applies it to the Activity and closes the
 * dialog. Back only dismisses the dialog and never changes the selection.
 */
@Composable
fun AppLanguagePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var selectedTag by remember { mutableStateOf(AppLanguage.currentTag(context)) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(configuration) {
        selectedTag = AppLanguage.currentTag(context)
    }

    val systemLabel = stringResource(R.string.app_language_system)
    val currentLabel = if (selectedTag.isEmpty()) systemLabel else languageName(selectedTag)

    MeshCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PICKER_TAG),
        shape = MaterialTheme.shapes.large,
        onClick = { expanded = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = MeshTheme.spacing.lg, vertical = MeshTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MeshTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = currentLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (!expanded) return

    AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.app_language_title)) },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                LanguageOption(
                    testTag = SYSTEM_OPTION_TAG,
                    label = systemLabel,
                    selected = selectedTag.isEmpty(),
                    onClick = {
                        val activity = context.findActivity()
                        if (activity != null) {
                            expanded = false
                            selectedTag = ""
                            AppLanguage.apply(activity, "")
                        }
                    },
                )
                for (tag in AppLanguage.supportedTags(context)) {
                    LanguageOption(
                        testTag = OPTION_TAG_PREFIX + tag,
                        label = languageName(tag),
                        selected = selectedTag == tag,
                        onClick = {
                            val activity = context.findActivity()
                            if (activity != null) {
                                expanded = false
                                selectedTag = tag
                                AppLanguage.apply(activity, tag)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { expanded = false }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LanguageOption(
    testTag: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The language name in that language: English, Русский, Deutsch, 中文（简体）... */
private fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).ifBlank { tag }
}

/** Activity lookup through the wrapper chain, as required for applying a locale from Compose. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        if (current.baseContext === current) break
        current = current.baseContext
    }
    return current as? Activity
}
