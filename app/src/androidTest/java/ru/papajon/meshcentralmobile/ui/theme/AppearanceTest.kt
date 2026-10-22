package ru.papajon.meshcentralmobile.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.components.AppearanceSection

class AppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: MeshAppearance

    @Before fun preserveAppearance() {
        val store = AppearancePreferences(app)
        original = store.read()
        store.setMode(MeshThemeMode.Light)
        store.setDynamicColor(false)
    }

    @After fun restoreAppearance() {
        AppearancePreferences(app).apply { setMode(original.mode); setDynamicColor(original.dynamicColor) }
    }

    @Test fun themeChangesImmediatelyWithoutDiscardingDraftAndPersists() {
        var observedPrimary: Color? = null
        compose.setContent {
            MeshCentralMobileTheme {
                val primary = MaterialTheme.colorScheme.primary
                SideEffect { observedPrimary = primary }
                var draft by rememberSaveable { mutableStateOf("") }
                Column {
                    AppearanceSection()
                    OutlinedTextField(draft, { draft = it }, label = { Text("Fixture draft") })
                    Text(if (MeshTheme.isDark) "fixture:dark" else "fixture:light")
                }
            }
        }
        compose.onNodeWithText("fixture:light").assertIsDisplayed()
        compose.onNodeWithText("Fixture draft").performTextInput("unsent-note")
        compose.onNodeWithText(compose.activity.getString(R.string.settings_theme_dark)).performClick().assertIsSelected()
        compose.onNodeWithText("fixture:dark").assertIsDisplayed()
        compose.onNodeWithText("unsent-note").assertIsDisplayed()
        compose.runOnIdle { assertEquals(MeshThemeMode.Dark, AppearancePreferences(app).read().mode) }
        compose.onNodeWithText(compose.activity.getString(R.string.settings_theme_light)).performClick().assertIsSelected()
        compose.onNodeWithText("fixture:light").assertIsDisplayed()
        compose.onNodeWithText("unsent-note").assertIsDisplayed()
        if (Build.VERSION.SDK_INT >= 31) {
            compose.onNodeWithText(compose.activity.getString(R.string.settings_dynamic_colors)).performClick()
            compose.runOnIdle {
                assertEquals(true, AppearancePreferences(app).read().dynamicColor)
                assertEquals(MeshLightScheme.primary, observedPrimary)
            }
            compose.onNodeWithText("unsent-note").assertIsDisplayed()
        }
    }

    @Test fun appearanceRoundTripKeepsBothIndependentPreferences() {
        val name = "redesign_isolated_appearance_test"
        val store = AppearancePreferences(app, name)
        try {
            store.setMode(MeshThemeMode.Dark)
            store.setDynamicColor(true)
            assertEquals(MeshAppearance(MeshThemeMode.Dark, true), AppearancePreferences(app, name).read())
            store.setMode(MeshThemeMode.System)
            assertEquals(MeshAppearance(MeshThemeMode.System, true), AppearancePreferences(app, name).read())
        } finally { store.preferences.edit().clear().commit() }
    }
}
