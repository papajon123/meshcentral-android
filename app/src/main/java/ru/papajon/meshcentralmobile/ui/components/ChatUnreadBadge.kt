package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.papajon.meshcentralmobile.R

@Composable
fun ChatUnreadBadge(count: Int) {
    if (count > 0) {
        val description = stringResource(R.string.chat_unread, count)
        Badge(Modifier.testTag("chat-unread").semantics { contentDescription = description }) { Text(count.toString()) }
    }
}

@Composable
fun ChatUnreadAction(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chat_unread, count)
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp).testTag("chat-unread-action")
        .semantics { contentDescription = description }) {
        BadgedBox(badge = { Badge { Text(count.toString()) } }) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
        }
    }
}
