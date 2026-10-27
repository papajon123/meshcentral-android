package ru.papajon.meshcentralmobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papajon.meshcentralmobile.BuildConfig
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

@RunWith(AndroidJUnit4::class)
class SupportCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun opensTheBuildSupportPageAndHidesWithoutALink() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        var url by mutableStateOf(BuildConfig.SUPPORT_URL)
        compose.setContent {
            MeshCentralMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) { SupportCard(url) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.support_title)).assertIsDisplayed()
        compose.onNodeWithTag("support-open").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("https://boosty.to/papajon123"), opened) }

        compose.runOnIdle { url = "" }
        compose.onNodeWithTag("support-card").assertDoesNotExist()
    }
}
