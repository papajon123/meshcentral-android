package ru.papajon.meshcentralmobile.settings

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyboardPreferenceTest {
    @Test
    fun remoteKeyboardMapDefaultsOffAndStaysIsolatedByProfileAndDevice() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = app.getSharedPreferences("desktop_display", 0)
        val before = saved.all
        val prefs = DesktopPreferences(app)
        try {
            assertFalse(prefs.loadRemoteKeyboardMap("fixture-a", "node-a"))

            prefs.saveRemoteKeyboardMap("fixture-a", "node-a", true)
            prefs.saveRemoteKeyboardMap("fixture-b", "node-a", false)

            val reopened = DesktopPreferences(app)
            assertTrue(reopened.loadRemoteKeyboardMap("fixture-a", "node-a"))
            assertFalse(reopened.loadRemoteKeyboardMap("fixture-b", "node-a"))
            assertFalse(reopened.loadRemoteKeyboardMap("fixture-a", "node-b"))
            assertFalse(reopened.loadRemoteKeyboardMap("fixture-b", "node-b"))

            reopened.saveRemoteKeyboardMap("fixture-a", "node-a", false)
            assertFalse(DesktopPreferences(app).loadRemoteKeyboardMap("fixture-a", "node-a"))
        } finally {
            val edit = saved.edit().clear()
            before.forEach { (key, value) ->
                when (value) {
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                }
            }
            edit.commit()
        }
    }
}
