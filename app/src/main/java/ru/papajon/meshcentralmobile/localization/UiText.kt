package ru.papajon.meshcentralmobile.localization

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Text that is resolved only when it is shown.
 *
 * Runtime state keeps a resource id and its format arguments instead of a formatted string, so a
 * language change re-renders every pending status in the new language. Text that genuinely comes
 * from a server (a close reason, a device name, a server result) is kept verbatim as [Literal].
 */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Literal(val text: String) : UiText

    companion object {
        fun resource(@StringRes id: Int, vararg args: Any): UiText = Resource(id, args.toList())

        fun literal(text: String): UiText = Literal(text)

        /** Server text, or null when there is nothing to show. */
        fun raw(text: String?): UiText? = text?.takeIf { it.isNotBlank() }?.let(::Literal)
    }
}

/** Resolves a message with the context resources; safe to call from callbacks and non-Compose code. */
fun UiText.asString(context: Context): String = when (this) {
    is UiText.Resource -> {
        val resolvedArgs = args.map { arg -> if (arg is UiText) arg.asString(context) else arg }
        context.getString(id, *resolvedArgs.toTypedArray())
    }
    is UiText.Literal -> text
}

/** Resolves a message during composition, so a locale change invalidates the rendered text. */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Resource -> {
        val resolvedArgs = args.map { arg -> if (arg is UiText) arg.asString() else arg }
        stringResource(id, *resolvedArgs.toTypedArray())
    }
    is UiText.Literal -> text
}

fun UiText?.asStringOrNull(context: Context): String? = this?.asString(context)
