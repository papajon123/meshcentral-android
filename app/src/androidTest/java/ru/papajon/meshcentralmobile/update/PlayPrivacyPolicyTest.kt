package ru.papajon.meshcentralmobile.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.components.PlayPrivacyPolicyButton
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class PlayPrivacyPolicyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun policyOpensOfflineWithoutAServerAccountAndCanBeDismissed() {
        assumeFalse(BuildConfig.EXTERNAL_UPDATES_ENABLED)
        compose.setContent { MeshCentralMobileTheme { PlayPrivacyPolicyButton() } }
        compose.onNodeWithTag("play-privacy-policy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("play-privacy-content").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("play-privacy-content").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.play_privacy_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.common_action_close)).performClick()
        compose.onNodeWithTag("play-privacy-dialog").assertDoesNotExist()
    }
}
