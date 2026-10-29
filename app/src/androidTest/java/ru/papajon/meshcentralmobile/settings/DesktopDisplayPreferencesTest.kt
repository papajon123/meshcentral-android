package ru.papajon.meshcentralmobile.settings

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DesktopDisplayPreferencesTest {
    @Test fun choicesSurviveRecreationAndStayIsolatedByProfileAndDevice() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val saved=app.getSharedPreferences("desktop_display",0)
        val before=saved.all
        val prefs=DesktopPreferences(app)
        try {
            prefs.saveDisplay("fixture-a","node-a",2)
            prefs.saveDisplay("fixture-b","node-a",65535)
            prefs.saveDisplay("fixture-a","node-b",7)
            val reopened=DesktopPreferences(app)
            assertEquals(2,reopened.loadDisplay("fixture-a","node-a"))
            assertEquals(65535,reopened.loadDisplay("fixture-b","node-a"))
            assertEquals(7,reopened.loadDisplay("fixture-a","node-b"))
            assertNull(reopened.loadDisplay("fixture-b","node-b"))
        } finally {
            val edit=saved.edit().clear()
            before.forEach { (key,value) -> when(value) {
                is Int -> edit.putInt(key,value)
                is Boolean -> edit.putBoolean(key,value)
                is String -> edit.putString(key,value)
                is Long -> edit.putLong(key,value)
                is Float -> edit.putFloat(key,value)
            } }
            edit.commit()
        }
    }
}
