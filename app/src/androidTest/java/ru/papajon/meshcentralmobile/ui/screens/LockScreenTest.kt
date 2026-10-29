package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
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

/** Actual keypad and authentication boundary; synthetic credentials only. */
class LockScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val submittedPins = mutableListOf<String>()

    @Test fun fourDigitsUnlockWithoutEnterIncludingZero() {
        showLock("6042", 4)
        enterDigits("604")
        compose.runOnIdle { assertEquals(emptyList<String>(), submittedPins) }
        enterDigits("2")
        awaitSubmission("6042")
        assertDigitCount(0)
        compose.onNodeWithText(compose.activity.getString(R.string.lock_action_unlock)).assertDoesNotExist()
    }

    @Test fun longPinIsNotRejectedAtDigitFourAndBackspaceCorrectsIt() {
        showLock("604203", 6)
        enterDigits("60429")
        backspace()
        compose.runOnIdle { assertEquals(emptyList<String>(), submittedPins) }
        assertDigitCount(4)
        enterDigits("03")
        awaitSubmission("604203")
        assertDigitCount(0)
    }

    @Test fun rejectedCompletePinClearsForRetryWithoutUnlocking() {
        showLock("6042", 4)
        enterDigits("1111")
        awaitSubmission("1111")
        compose.onNodeWithText(compose.activity.getString(R.string.lock_invalid_pin)).performScrollTo().assertIsDisplayed()
        assertDigitCount(0)
        enterDigits("6042")
        awaitSubmission("6042")
        compose.runOnIdle { assertEquals(listOf("1111", "6042"), submittedPins) }
    }

    @Test fun twelveDigitsAreAcceptedWithoutAnExtraAction() {
        showLock("604260426042", 12)
        enterDigits("604260426042")
        awaitSubmission("604260426042")
        assertDigitCount(0)
    }

    @Test fun legacyLongPinAutoUnlocksWithoutRejectingItsPrefix() {
        showLock("604203", null)
        enterDigits("6042")
        awaitSubmission("6042")
        compose.onNodeWithText(compose.activity.getString(R.string.lock_invalid_pin)).assertDoesNotExist()
        assertDigitCount(4)
        enterDigits("03")
        awaitSubmission("604203")
        assertDigitCount(0)
    }

    @Test fun biometricsOpenOnceAndCancellationLeavesWorkingPin() {
        var prompts = 0
        var callback: ((Boolean, String?) -> Unit)? = null
        val recompose = mutableStateOf(false)
        compose.setContent {
            MeshCentralMobileTheme(darkTheme = recompose.value) {
                LockScreen(true, { submittedPins += it; it == "6042" }, {
                    prompts += 1
                    callback = it
                }, pinLength = 4)
            }
        }
        compose.waitUntil { prompts == 1 }
        compose.runOnIdle { callback!!(false, null); recompose.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.lock_invalid_pin)).assertDoesNotExist()
        enterDigits("6042")
        awaitSubmission("6042")
        compose.runOnIdle { assertEquals(1, prompts) }
    }

    @Test fun recreationDoesNotRestorePinOrReopenDismissedBiometrics() {
        val restoration = StateRestorationTester(compose)
        var prompts = 0
        restoration.setContent {
            MeshCentralMobileTheme {
                LockScreen(true, { false }, { prompts += 1; it(false, null) }, pinLength = 6)
            }
        }
        compose.waitUntil { prompts == 1 }
        enterDigits("604")
        assertDigitCount(3)
        restoration.emulateSavedInstanceStateRestore()
        assertDigitCount(0)
        compose.runOnIdle { assertEquals(1, prompts) }
        compose.onNodeWithText(compose.activity.getString(R.string.lock_action_unlock_biometric))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, prompts) }
    }

    private fun showLock(correctPin: String, length: Int?) {
        compose.setContent {
            MeshCentralMobileTheme {
                LockScreen(false, { submittedPins += it; it == correctPin }, {}, pinLength = length)
            }
        }
    }

    private fun enterDigits(digits: String) = digits.forEach {
        compose.onNodeWithText(it.toString()).performScrollTo().performClick()
    }

    private fun awaitSubmission(pin: String) {
        compose.waitUntil(5_000) { pin in submittedPins }
        compose.waitForIdle()
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
