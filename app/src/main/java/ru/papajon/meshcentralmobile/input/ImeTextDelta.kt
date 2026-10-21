package ru.papajon.meshcentralmobile.input

/** Maps Android IME composition updates to remote text and deletion events. */
internal class ImeTextDelta(
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onDelete: () -> Unit = {},
) {
    private var composition = ""

    fun setComposingText(text: String) {
        replaceComposition(text)
    }

    fun commitText(text: String) {
        replaceComposition(text)
        composition = ""
    }

    /** Native key events and setComposingRegion can change the editor without a text commit. */
    fun synchronizeComposition(text: String) {
        composition = text
    }

    fun finishComposingText() {
        composition = ""
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        repeat(beforeLength.coerceAtLeast(0)) { onBackspace() }
        repeat(afterLength.coerceAtLeast(0)) { onDelete() }
        composition = composition.dropLast(beforeLength.coerceAtLeast(0))
    }

    private fun replaceComposition(next: String) {
        var shared = composition.commonPrefixWith(next).length
        if (shared > 0 && shared < composition.length && Character.isHighSurrogate(composition[shared - 1])) shared--
        repeat(composition.codePointCount(shared, composition.length)) { onBackspace() }
        next.substring(shared).takeIf { it.isNotEmpty() }?.let(onText)
        composition = next
    }
}
