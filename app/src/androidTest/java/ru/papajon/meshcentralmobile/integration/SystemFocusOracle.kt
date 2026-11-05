package ru.papajon.meshcentralmobile.integration

import android.app.UiAutomation
import android.os.ParcelFileDescriptor

internal enum class FocusSource {
    CURRENT_FOCUS,
    FOCUSED_APP,
}

internal data class FocusComponent(
    val packageName: String,
    val activityName: String,
    val source: FocusSource,
) {
    val isDocumentsUi: Boolean
        get() = packageName in DOCUMENTS_UI_PACKAGES

    override fun toString(): String = "$packageName/$activityName via ${source.name}"
}

internal data class WindowFocusSnapshot(
    val currentFocus: FocusComponent?,
    val focusedApp: FocusComponent?,
    val currentFocusRecorded: Boolean,
    val focusedAppRecorded: Boolean,
    val currentFocusHadWindow: Boolean,
) {
    /**
     * The current-focus record is authoritative for the app that owns the focused window.
     * A system input window (for example an IME) may have no activity component; only in
     * that case does the focused-app record name the activity that owns the focused window.
     * A null or missing current focus is not an input window, so it must not fall back.
     */
    val preferredComponent: FocusComponent?
        get() = currentFocus ?: focusedApp.takeIf { currentFocusHadWindow }

    val documentsUiForeground: Boolean
        get() = preferredComponent?.isDocumentsUi == true
}

/**
 * Parses only `mCurrentFocus` and `mFocusedApp` from a `dumpsys window` dump. The dump itself
 * is never retained or logged: callers stream lines into [parse].
 */
internal object WindowFocusParser {
    private const val CURRENT_FOCUS_PREFIX = "mCurrentFocus="
    private const val FOCUSED_APP_PREFIX = "mFocusedApp="

    private val COMPONENT = Regex(
        """([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.${'$'}]+)""",
    )

    fun parse(lines: Sequence<String>): WindowFocusSnapshot {
        var currentFocus: FocusComponent? = null
        var focusedApp: FocusComponent? = null
        var currentFocusRecorded = false
        var focusedAppRecorded = false
        var currentFocusHadWindow = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                !currentFocusRecorded && line.startsWith(CURRENT_FOCUS_PREFIX) -> {
                    currentFocusRecorded = true
                    currentFocusHadWindow = line.removePrefix(CURRENT_FOCUS_PREFIX).contains('{')
                    currentFocus = componentAfterPrefix(line, CURRENT_FOCUS_PREFIX, FocusSource.CURRENT_FOCUS)
                }
                !focusedAppRecorded && line.startsWith(FOCUSED_APP_PREFIX) -> {
                    focusedAppRecorded = true
                    focusedApp = componentAfterPrefix(line, FOCUSED_APP_PREFIX, FocusSource.FOCUSED_APP)
                }
            }
            if (currentFocusRecorded && focusedAppRecorded) break
        }
        return WindowFocusSnapshot(
            currentFocus = currentFocus,
            focusedApp = focusedApp,
            currentFocusRecorded = currentFocusRecorded,
            focusedAppRecorded = focusedAppRecorded,
            currentFocusHadWindow = currentFocusHadWindow,
        )
    }

    private fun componentAfterPrefix(line: String, prefix: String, source: FocusSource): FocusComponent? {
        val value = line.removePrefix(prefix)
        val openBrace = value.indexOf('{')
        if (openBrace < 0) return null
        val closeBrace = value.indexOf('}', startIndex = openBrace + 1)
        val record = if (closeBrace >= 0) {
            value.substring(openBrace + 1, closeBrace)
        } else {
            value.substring(openBrace + 1)
        }
        if (record.isBlank()) return null
        val match = COMPONENT.find(record) ?: return null
        return FocusComponent(
            packageName = match.groupValues[1],
            activityName = match.groupValues[2],
            source = source,
        )
    }
}

/**
 * Independent, test-only foreground oracle backed by Window Manager state rather than the
 * accessibility tree that [android.view.accessibility.AccessibilityNodeInfo] exposes.
 */
internal class SystemFocusOracle(private val automation: UiAutomation) {
    fun readSnapshot(): WindowFocusSnapshot =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(DUMP_COMMAND))
            .bufferedReader()
            .useLines { lines -> WindowFocusParser.parse(lines) }

    fun documentsUiForeground(): Boolean = readSnapshot().documentsUiForeground

    private companion object {
        private const val DUMP_COMMAND = "dumpsys window"
    }
}

internal val DOCUMENTS_UI_PACKAGES = setOf(
    "com.android.documentsui",
    "com.google.android.documentsui",
)
