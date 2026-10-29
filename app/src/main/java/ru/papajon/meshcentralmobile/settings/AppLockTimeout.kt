package ru.papajon.meshcentralmobile.settings

/** One supported range for editing, persistence and backup; zero locks on leaving the app. */
object AppLockTimeout {
    val range = 0L..86_400L

    fun parse(text: String): Long? = text.trim().takeIf { value ->
        value.isNotEmpty() && value.all { it in '0'..'9' }
    }?.toLongOrNull()?.takeIf { it in range }
}
