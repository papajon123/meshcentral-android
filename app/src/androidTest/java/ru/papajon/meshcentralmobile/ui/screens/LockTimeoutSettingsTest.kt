package ru.papajon.meshcentralmobile.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class LockTimeoutSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("lock-delay-test-$name", mode)
        }
        settings = SettingsRepository(context)
        settings.updateLockTimeoutSeconds(60)
        compose.setContent {
            val value by settings.settingsState
            MeshCentralMobileTheme { SettingsAutoLockRow(value.lockTimeoutSeconds, settings::updateLockTimeoutSeconds) }
        }
    }

    @After fun cleanup() { settings.updateLockTimeoutSeconds(60) }

    @Test fun customTimeIsPersistedAndDisplayedAfterReopening() {
        compose.onNodeWithTag("lock-timeout-row").performClick()
        compose.onNodeWithTag("lock-timeout-input").performTextReplacement("90")
        compose.onNodeWithTag("lock-timeout-save").performClick()
        compose.onNodeWithTag("lock-timeout-input").assertDoesNotExist()
        assertEquals(90L, SettingsRepository(context).getSettings().lockTimeoutSeconds)
        compose.onNodeWithText(context.getString(R.string.settings_auto_lock_seconds, 90L)).assertIsDisplayed()
        compose.onNodeWithTag("lock-timeout-row").performClick()
        compose.onNodeWithTag("lock-timeout-input").assertTextContains("90")
    }

    @Test fun invalidInputAndCancellationDoNotChangeSavedTime() {
        compose.onNodeWithTag("lock-timeout-row").performClick()
        listOf("", "-1", "1.5", "86401", "99999999999999999999").forEach {
            compose.onNodeWithTag("lock-timeout-input").performTextReplacement(it)
            compose.onNodeWithTag("lock-timeout-save").assertIsNotEnabled()
            assertEquals(60L, SettingsRepository(context).getSettings().lockTimeoutSeconds)
        }
        compose.onNodeWithTag("lock-timeout-input").performTextReplacement("45")
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(60L, SettingsRepository(context).getSettings().lockTimeoutSeconds)
    }

    @Test fun zeroCanBeSavedThroughTheKeyboardAction() {
        compose.onNodeWithTag("lock-timeout-row").performClick()
        compose.onNodeWithTag("lock-timeout-input").performTextReplacement("0")
        compose.onNodeWithTag("lock-timeout-input").performImeAction()
        compose.onNodeWithTag("lock-timeout-input").assertDoesNotExist()
        assertEquals(0L, SettingsRepository(context).getSettings().lockTimeoutSeconds)
    }

    @Test fun zeroLocksWhenTheLastWindowLeavesAndNotWhileItIsVisible() {
        compose.runOnIdle {
            val vault = ru.papajon.meshcentralmobile.security.SecureVault(context)
            vault.savePin("6042")
            val lock = ru.papajon.meshcentralmobile.settings.AppLockController(settings, vault)
            val window = Any()
            try {
                settings.updateLockTimeoutSeconds(0)
                lock.onWindowStarted(window, false)
                assertTrue(lock.unlockWithPin("6042"))
                lock.onAppForegrounded()
                assertFalse(lock.isLocked)
                lock.onWindowStopped(window)
                lock.onAppForegrounded()
                assertTrue(lock.isLocked)
            } finally {
                vault.clearPin()
                lock.refreshLockRequirement()
            }
        }
    }
}
