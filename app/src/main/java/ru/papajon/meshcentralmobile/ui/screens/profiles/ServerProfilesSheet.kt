package ru.papajon.meshcentralmobile.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.ui.components.EmptyState
import ru.papajon.meshcentralmobile.ui.components.IconBadge
import ru.papajon.meshcentralmobile.ui.components.MeshCard
import ru.papajon.meshcentralmobile.ui.components.MeshSheet
import ru.papajon.meshcentralmobile.ui.components.SheetHeader
import ru.papajon.meshcentralmobile.ui.theme.MeshTheme
import ru.papajon.meshcentralmobile.ui.theme.PillShape

/** Stable semantics tags shared with the instrumentation tests for this sheet. */
object ServerProfilesTestTags {
    const val Sheet = "server_profiles_sheet"
    const val AddAction = "server_profiles_add_action"
    const val AddForm = "server_profiles_add_form"
    const val NameField = "server_profiles_name_field"
    const val UrlField = "server_profiles_url_field"
    const val SubmitAdd = "server_profiles_submit_add"
    const val ErrorBanner = "server_profiles_error_banner"
    const val RenameField = "server_profiles_rename_field"
    const val ConfirmRename = "server_profiles_confirm_rename"
    const val CancelRename = "server_profiles_cancel_rename"
    const val ConfirmDelete = "server_profiles_confirm_delete"
    const val CancelDelete = "server_profiles_cancel_delete"

    fun row(id: String) = "server_profiles_row_$id"
    fun activate(id: String) = "server_profiles_activate_$id"
    fun menu(id: String) = "server_profiles_menu_$id"
    fun rename(id: String) = "server_profiles_rename_$id"
    fun delete(id: String) = "server_profiles_delete_$id"
}

/**
 * Stateless management UI for saved servers.
 *
 * All persistent decisions stay with the caller: [onCreate] receives the trimmed name and address,
 * [onRename] and [onDelete] receive the profile id only, and [onActivate] is the sole way to select
 * a profile. This composable owns just transient UI state (open form, drafts, dialogs, menu).
 *
 * A callback failure is surfaced through [error]; the add form deliberately stays open and its
 * `rememberSaveable` draft is never rebuilt from [profiles], so the user does not have to retype it.
 */
@Composable
fun ServerProfilesSheet(
    profiles: List<ServerProfile>,
    activeProfileId: String,
    onActivate: (String) -> Unit,
    onCreate: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    error: String? = null,
) {
    var addingProfile by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var draftUrl by rememberSaveable { mutableStateOf("") }
    var draftNameMissing by rememberSaveable { mutableStateOf(false) }
    var draftUrlMissing by rememberSaveable { mutableStateOf(false) }

    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var renameMissing by rememberSaveable { mutableStateOf(false) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val spacing = MeshTheme.spacing
    val errorMessage = error?.takeIf { it.isNotBlank() }
    val renameTarget = profiles.firstOrNull { it.id == renameTargetId }
    val deleteTarget = profiles.firstOrNull { it.id == deleteTargetId }

    fun submitDraft() {
        val nameOk = draftName.isNotBlank()
        val urlOk = draftUrl.isNotBlank()
        draftNameMissing = !nameOk
        draftUrlMissing = !urlOk
        if (nameOk && urlOk) {
            onCreate(draftName.trim(), draftUrl.trim())
        }
    }

    MeshSheet(onDismissRequest = onClose) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ServerProfilesTestTags.Sheet)
                // safeDrawing includes the IME inset, so the add form clears the keyboard without
                // adding a second bottom inset on top of the sheet's own window insets.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                // safeDrawing already covers the keyboard; the consumed insets make this a no-op
                // on API levels where the sheet itself does not apply them.
                .imePadding(),
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "header") {
                SheetHeader(
                    title = stringResource(
                        if (addingProfile) R.string.profile_add_title else R.string.profile_sheet_title,
                    ),
                    subtitle = stringResource(
                        if (addingProfile) R.string.profile_add_subtitle else R.string.profile_sheet_subtitle,
                    ),
                    trailing = {
                        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.profile_close),
                            )
                        }
                    },
                )
            }

            if (errorMessage != null && !addingProfile) {
                item(key = "error") {
                    ProfileErrorBanner(
                        message = errorMessage,
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            }

            if (addingProfile) {
                item(key = "add-form") {
                    AddProfileForm(
                        name = draftName,
                        url = draftUrl,
                        nameMissing = draftNameMissing,
                        urlMissing = draftUrlMissing,
                        error = errorMessage,
                        onNameChange = {
                            draftName = it.take(80)
                            draftNameMissing = false
                        },
                        onUrlChange = {
                            draftUrl = it.take(2048)
                            draftUrlMissing = false
                        },
                        onSubmit = ::submitDraft,
                        onBack = { addingProfile = false },
                        modifier = Modifier.padding(horizontal = spacing.xl),
                    )
                }
            } else {
                if (profiles.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.Dns,
                            title = stringResource(R.string.profile_empty_title),
                            description = stringResource(R.string.profile_empty_description),
                            actionLabel = stringResource(R.string.profile_add),
                            onAction = { addingProfile = true },
                        )
                    }
                } else {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == activeProfileId,
                            canDelete = profiles.size > 1,
                            onActivate = { onActivate(profile.id) },
                            onRename = {
                                renameTargetId = profile.id
                                renameDraft = profile.name
                                renameMissing = false
                            },
                            onDelete = { deleteTargetId = profile.id },
                            modifier = Modifier.padding(horizontal = spacing.xl),
                        )
                    }
                    item(key = "add-action") {
                        Button(
                            onClick = { addingProfile = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.xl)
                                .heightIn(min = 56.dp)
                                .testTag(ServerProfilesTestTags.AddAction),
                            shape = PillShape,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.profile_add))
                        }
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        RenameProfileDialog(
            name = renameDraft,
            nameMissing = renameMissing,
            onNameChange = {
                renameDraft = it.take(80)
                renameMissing = false
            },
            onConfirm = {
                if (renameDraft.isBlank()) {
                    renameMissing = true
                } else {
                    val targetId = renameTarget.id
                    renameTargetId = null
                    onRename(targetId, renameDraft.trim())
                }
            },
            onDismiss = {
                renameTargetId = null
                renameMissing = false
            },
        )
    }

    if (deleteTarget != null) {
        val unnamed = stringResource(R.string.profile_unnamed)
        val profileName = if (deleteTarget.name.isBlank()) unnamed else deleteTarget.name
        DeleteProfileDialog(
            profileName = profileName,
            canDelete = profiles.size > 1,
            onConfirm = {
                val targetId = deleteTarget.id
                deleteTargetId = null
                if (profiles.size > 1) onDelete(targetId)
            },
            onDismiss = { deleteTargetId = null },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    var menuExpanded by remember { mutableStateOf(false) }
    val fallbackName = stringResource(R.string.profile_unnamed)
    val displayName = if (profile.name.isBlank()) fallbackName else profile.name
    val activeDescription = stringResource(R.string.profile_active_description, displayName)
    val selectDescription = stringResource(R.string.profile_select_description, displayName)
    val actionsDescription = stringResource(R.string.profile_actions_description, displayName)

    MeshCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ServerProfilesTestTags.row(profile.id)),
        shape = MaterialTheme.shapes.large,
        container = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            IconBadge(
                icon = if (isActive) Icons.Outlined.Check else Icons.Outlined.Dns,
                size = 40.dp,
                container = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                tint = if (isActive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.serverUrl,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isActive) {
                FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    shape = PillShape,
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .testTag(ServerProfilesTestTags.activate(profile.id))
                        .semantics { contentDescription = activeDescription },
                ) {
                    Text(stringResource(R.string.profile_active))
                }
            } else {
                OutlinedButton(
                    onClick = onActivate,
                    shape = PillShape,
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .testTag(ServerProfilesTestTags.activate(profile.id))
                        .semantics { contentDescription = selectDescription },
                ) {
                    Text(stringResource(R.string.profile_select))
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(ServerProfilesTestTags.menu(profile.id)),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = actionsDescription,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_rename)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.testTag(ServerProfilesTestTags.rename(profile.id)),
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.profile_delete))
                                if (!canDelete) {
                                    Text(
                                        text = stringResource(R.string.profile_last_cannot_delete),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = if (canDelete) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        enabled = canDelete,
                        modifier = Modifier.testTag(ServerProfilesTestTags.delete(profile.id)),
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProfileForm(
    name: String,
    url: String,
    nameMissing: Boolean,
    urlMissing: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeshTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ServerProfilesTestTags.AddForm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ServerProfilesTestTags.NameField),
            label = { Text(stringResource(R.string.profile_name_label)) },
            placeholder = { Text(stringResource(R.string.profile_name_placeholder)) },
            singleLine = true,
            isError = nameMissing,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            supportingText = if (nameMissing) {
                { Text(stringResource(R.string.profile_name_required)) }
            } else {
                null
            },
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ServerProfilesTestTags.UrlField),
            label = { Text(stringResource(R.string.profile_url_label)) },
            placeholder = {
                Text(
                    text = stringResource(R.string.profile_url_placeholder),
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                )
            },
            singleLine = true,
            isError = urlMissing,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            supportingText = if (urlMissing) {
                { Text(stringResource(R.string.profile_url_required)) }
            } else {
                null
            },
        )

        if (error != null) {
            ProfileErrorBanner(message = error)
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(ServerProfilesTestTags.SubmitAdd),
            shape = PillShape,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(R.string.profile_submit),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.profile_back))
        }
    }
}

@Composable
private fun RenameProfileDialog(
    name: String,
    nameMissing: Boolean,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ServerProfilesTestTags.RenameField),
                label = { Text(stringResource(R.string.profile_name_label)) },
                singleLine = true,
                isError = nameMissing,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                supportingText = if (nameMissing) {
                    { Text(stringResource(R.string.profile_name_required)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(ServerProfilesTestTags.ConfirmRename),
            ) {
                Text(stringResource(R.string.profile_rename_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ServerProfilesTestTags.CancelRename),
            ) {
                Text(stringResource(R.string.profile_cancel))
            }
        },
    )
}

@Composable
private fun DeleteProfileDialog(
    profileName: String,
    canDelete: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_delete_title, profileName)) },
        text = { Text(stringResource(R.string.profile_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag(ServerProfilesTestTags.ConfirmDelete),
            ) {
                Text(stringResource(R.string.profile_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ServerProfilesTestTags.CancelDelete),
            ) {
                Text(stringResource(R.string.profile_cancel))
            }
        },
    )
}

@Composable
private fun ProfileErrorBanner(message: String, modifier: Modifier = Modifier) {
    val spacing = MeshTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ServerProfilesTestTags.ErrorBanner),
            )
        }
    }
}
