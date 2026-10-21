package ru.papajon.meshcentralmobile.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeTextDeltaTest {
    @Test
    fun emojiReplacementDoesNotSplitASurrogatePair() {
        val events = mutableListOf<String>()
        val forwarder = ImeTextDelta(onText = { events += it }, onBackspace = { events += "delete" })
        forwarder.setComposingText("😀")
        forwarder.setComposingText("😁")
        assertEquals(listOf("😀", "delete", "😁"), events)
    }

    @Test
    fun composingTextAppearsImmediatelyAndCommitDoesNotDuplicateIt() {
        val events = mutableListOf<String>()
        val forwarder = ImeTextDelta(
            onText = { events += "text:$it" },
            onBackspace = { events += "backspace" },
        )

        forwarder.setComposingText("п")
        forwarder.setComposingText("пр")
        forwarder.commitText("пр")
        forwarder.commitText("ивет")

        assertEquals(listOf("text:п", "text:р", "text:ивет"), events)
    }

    @Test
    fun correctedCompositionReplacesOnlyItsChangedSuffix() {
        val events = mutableListOf<String>()
        val forwarder = ImeTextDelta(
            onText = { events += "text:$it" },
            onBackspace = { events += "backspace" },
        )

        forwarder.setComposingText("теx")
        forwarder.commitText("тех")

        assertEquals(listOf("text:теx", "backspace", "text:х"), events)
    }

    @Test
    fun emptyCommitDeletesComposingWordJustLikeTheAndroidEditor() {
        val events = mutableListOf<String>()
        val forwarder = ImeTextDelta(
            onText = { events += "text:$it" },
            onBackspace = { events += "backspace" },
        )

        forwarder.setComposingText("тест")
        forwarder.commitText("")

        assertEquals(listOf("text:тест", "backspace", "backspace", "backspace", "backspace"), events)
    }
}
