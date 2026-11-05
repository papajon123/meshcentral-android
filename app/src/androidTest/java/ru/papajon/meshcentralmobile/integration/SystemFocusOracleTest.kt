package ru.papajon.meshcentralmobile.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the test-only Window Manager focus oracle. All dumps are synthetic;
 * caller ids and app packages are made up and must not contain local device identifiers.
 */
class SystemFocusOracleTest {
    @Test
    fun android26ShapedCurrentFocusRecordNamesDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=Window{0a1b2c3 u0 com.android.documentsui/com.android.documentsui.picker.PickActivity}",
            "  mFocusedApp=ActivityRecord{4d5e6f7 u0 com.android.documentsui/.picker.PickActivity t42}",
        )

        assertTrue(snapshot.currentFocusRecorded)
        assertTrue(snapshot.focusedAppRecorded)
        assertTrue(snapshot.documentsUiForeground)
        assertEquals(FocusSource.CURRENT_FOCUS, snapshot.preferredComponent?.source)
        assertEquals("com.android.documentsui", snapshot.preferredComponent?.packageName)
        assertEquals(
            "com.android.documentsui.picker.PickActivity",
            snapshot.preferredComponent?.activityName,
        )
    }

    @Test
    fun android35ShapedCurrentFocusAndFocusedAppRecordsNameGoogleDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=Window{1a233c5 u0 com.google.android.documentsui/com.google.android.documentsui.picker.PickActivity}",
            "  mFocusedApp=ActivityRecord{895bf3d u0 com.google.android.documentsui/.picker.PickActivity t109}",
        )

        assertTrue(snapshot.documentsUiForeground)
        assertEquals(FocusSource.CURRENT_FOCUS, snapshot.preferredComponent?.source)
        assertEquals("com.google.android.documentsui", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun inputMethodWindowWithoutActivityComponentFallsBackToFocusedDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=Window{77aa001 u0 InputMethod}",
            "  mFocusedApp=ActivityRecord{88bb002 u0 com.android.documentsui/.picker.PickActivity t7}",
        )

        assertNull(snapshot.currentFocus)
        assertTrue(snapshot.currentFocusHadWindow)
        assertTrue(snapshot.documentsUiForeground)
        assertEquals(FocusSource.FOCUSED_APP, snapshot.preferredComponent?.source)
        assertEquals("com.android.documentsui", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun nullCurrentFocusDoesNotFallBackToDocumentsUiFocusedApp() {
        val snapshot = parse(
            "  mCurrentFocus=null",
            "  mFocusedApp=ActivityRecord{ab12cd3 u0 com.android.documentsui/.picker.PickActivity t21}",
        )

        assertTrue(snapshot.currentFocusRecorded)
        assertFalse(snapshot.currentFocusHadWindow)
        assertNull(snapshot.preferredComponent)
        assertFalse(snapshot.documentsUiForeground)
    }

    @Test
    fun currentFocusDocumentsUiWinsOverUnrelatedFocusedAppRecord() {
        val snapshot = parse(
            "  mCurrentFocus=Window{e0f1a02 u0 com.android.documentsui/com.android.documentsui.picker.PickActivity}",
            "  mFocusedApp=ActivityRecord{f1a2b03 u0 com.example.synthetic.notes/.MainActivity t13}",
        )

        assertTrue(snapshot.documentsUiForeground)
        assertEquals(FocusSource.CURRENT_FOCUS, snapshot.preferredComponent?.source)
        assertEquals("com.android.documentsui", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun unrelatedFocusedAppWinsOverStaleDocumentsUiFocusedAppRecord() {
        val snapshot = parse(
            "  mCurrentFocus=Window{cc001 u0 com.example.synthetic.notes/com.example.synthetic.notes.EditorActivity}",
            "  mFocusedApp=ActivityRecord{dd002 u0 com.android.documentsui/.picker.PickActivity t5}",
        )

        assertFalse(snapshot.documentsUiForeground)
        assertEquals(FocusSource.CURRENT_FOCUS, snapshot.preferredComponent?.source)
        assertEquals("com.example.synthetic.notes", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun backgroundAndLastPausedDocumentsUiRecordsAreNotForegroundEvidence() {
        val snapshot = parse(
            "  mCurrentFocus=Window{ff004 u0 com.example.synthetic.notes/com.example.synthetic.notes.MainActivity}",
            "  mFocusedApp=ActivityRecord{aa005 u0 com.example.synthetic.notes/.MainActivity t3}",
            "  mLastPausedActivity=ActivityRecord{bb006 u0 com.android.documentsui/.picker.PickActivity t9}",
            "  mLastFocusedWindow=Window{cc007 u0 com.android.documentsui/com.android.documentsui.picker.PickActivity}",
        )

        assertFalse(snapshot.documentsUiForeground)
        assertEquals("com.example.synthetic.notes", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun nullFocusRecordsAreNotDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=null",
            "  mFocusedApp=null",
        )

        assertTrue(snapshot.currentFocusRecorded)
        assertTrue(snapshot.focusedAppRecorded)
        assertNull(snapshot.preferredComponent)
        assertFalse(snapshot.documentsUiForeground)
    }

    @Test
    fun malformedFocusTextIsIgnoredWithoutThrowing() {
        val snapshot = parse(
            "  mCurrentFocus=Window{not-a-window",
            "  mFocusedApp=ActivityRecord{",
            "garbage line mentioning com.android.documentsui/.picker.PickActivity",
            "  mLastPausedActivity=ActivityRecord{ee003 u0 com.android.documentsui/.picker.PickActivity t9}",
        )

        assertNull(snapshot.currentFocus)
        assertNull(snapshot.focusedApp)
        assertNull(snapshot.preferredComponent)
        assertFalse(snapshot.documentsUiForeground)
    }

    @Test
    fun lookalikePackageIsNotAcceptedAsDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=Window{123abcd u0 com.android.documentsui.clone/com.android.documentsui.clone.picker.PickActivity}",
        )

        assertFalse(snapshot.documentsUiForeground)
        assertEquals("com.android.documentsui.clone", snapshot.preferredComponent?.packageName)
    }

    @Test
    fun systemWindowWithoutOwnActivityAndUnrelatedFocusedAppIsNotDocumentsUi() {
        val snapshot = parse(
            "  mCurrentFocus=Window{456ef01 u0 InputMethod}",
            "  mFocusedApp=ActivityRecord{789ab02 u0 com.example.synthetic.notes/.EditorActivity t11}",
        )

        assertFalse(snapshot.documentsUiForeground)
        assertEquals("com.example.synthetic.notes", snapshot.preferredComponent?.packageName)
    }

    private fun parse(vararg lines: String): WindowFocusSnapshot =
        WindowFocusParser.parse(lines.asSequence())
}
