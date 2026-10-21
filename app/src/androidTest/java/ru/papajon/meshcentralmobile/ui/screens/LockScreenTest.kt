package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Exercise the actual keypad and submit callback with synthetic PINs. */
class LockScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val submittedPins = mutableListOf<String>()

    @Test fun zeroDigitIsSubmittedAndAcceptedPinClearsTheEntry() {
        showLock(acceptPin = true)

        enterDigits("6042")
        unlock()

        compose.runOnIdle { assertEquals(listOf("6042"), submittedPins) }
        assertDigitCount(0)
    }

    @Test fun backspaceRemovesOnlyTheLastDigit() {
        showLock()

        enterDigits("6042")
        backspace()
        assertDigitCount(3)
        unlock()

        compose.runOnIdle { assertEquals(listOf("604"), submittedPins) }
        enterDigits("2")
        unlock()
        compose.runOnIdle { assertEquals(listOf("604", "6042"), submittedPins) }
    }

    @Test fun repeatedRejectedPinsStayCappedAtTwelveDigits() {
        showLock()
        val limitPin = "604260426042"
        enterDigits(limitPin)
        unlock()

        compose.onNodeWithText(compose.activity.getString(R.string.lock_invalid_pin))
            .performScrollTo().assertIsDisplayed()
        // Both the repaired zero key and the other digit keys must respect the same cap.
        repeat(2) {
            enterDigits("0123456789")
            assertDigitCount(12)
            unlock()
        }
        compose.runOnIdle { assertEquals(List(3) { limitPin }, submittedPins) }

        // A rejection must still permit correction at the cap.
        backspace()
        enterDigits("0")
        unlock()
        compose.runOnIdle {
            assertEquals(limitPin.dropLast(1) + "0", submittedPins.last())
        }
    }

    private fun showLock(acceptPin: Boolean = false) {
        compose.setContent {
            MeshCentralMobileTheme {
                LockScreen(
                    biometricEnabled = false,
                    onPinSubmit = { pin -> submittedPins += pin; acceptPin },
                    onBiometricUnlockRequested = {},
                )
            }
        }
    }

    private fun enterDigits(digits: String) {
        digits.forEach { digit ->
            compose.onNodeWithText(digit.toString()).performScrollTo().performClick()
        }
    }

    private fun unlock() {
        compose.onNodeWithText(compose.activity.getString(R.string.lock_action_unlock))
            .performScrollTo().performClick()
    }

    private fun backspace() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.lock_delete_digit))
            .performScrollTo().performClick()
    }

    private fun assertDigitCount(count: Int) {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.lock_pin_progress, count))
            .performScrollTo().assertIsDisplayed()
    }
}
