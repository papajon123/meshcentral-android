package ru.papajon.meshcentralmobile.mesh

import org.junit.Assert.*
import org.junit.Test

class DesktopDisplaySelectionTest {
    @Test fun agentDefaultCannotReplaceTheSelectedMonitorOrAll() {
        val ids=listOf(65535,1,2)
        assertEquals(2, preferredDisplaySelection(2,ids,65535))
        assertEquals(65535, preferredDisplaySelection(65535,ids,1))
    }
    @Test fun freshSessionsKeepTheInitialReadableMonitorAndMissingMonitorsFallBack() {
        assertEquals(1, preferredDisplaySelection(null,listOf(65535,1,2),65535))
        assertEquals(7, preferredDisplaySelection(null,listOf(65535,2,7),7))
        assertEquals(1, preferredDisplaySelection(2,listOf(65535,1),65535))
        assertEquals(2, preferredDisplaySelection(2,emptyList(),65535))
        assertEquals(65535, preferredDisplaySelection(null,listOf(0),65535))
    }
}
