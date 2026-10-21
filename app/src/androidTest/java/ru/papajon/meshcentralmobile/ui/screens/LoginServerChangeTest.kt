package ru.papajon.meshcentralmobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.mesh.MeshClientState
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class LoginServerChangeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingServerClearsInProgressUsernameAndPassword() {
        val server = mutableStateOf("https://first.example/")
        compose.setContent {
            MeshCentralMobileTheme {
                LoginContent(clientState = MeshClientState(), serverUrl = server.value)
            }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("fixture-user")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("fixture-password")
        compose.runOnIdle { server.value = "https://second.example/" }
        compose.waitForIdle()
        repeat(2) { index ->
            compose.onAllNodes(hasSetTextAction())[index].assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
        }
    }
}
