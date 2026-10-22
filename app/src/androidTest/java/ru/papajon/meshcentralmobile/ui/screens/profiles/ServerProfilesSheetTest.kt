package ru.papajon.meshcentralmobile.ui.screens.profiles

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class ServerProfilesSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val alpha = ServerProfile(
        id = "alpha",
        name = "Alpha",
        serverUrl = "https://alpha.example/",
    )
    private val beta = ServerProfile(
        id = "beta",
        name = "Beta",
        serverUrl = "https://beta.example/",
    )

    @Test
    fun selectingAProfileSendsItsExactId() {
        var selectedId: String? = null
        show(listOf(alpha, beta), onActivate = { selectedId = it })

        compose.onNodeWithTag(ServerProfilesTestTags.activate(beta.id)).performClick()

        compose.runOnIdle { assertEquals("beta", selectedId) }
    }

    @Test
    fun validDraftIsSentToTheCreateCallback() {
        var createdName: String? = null
        var createdUrl: String? = null
        show(
            profiles = listOf(alpha),
            onCreate = { name, url ->
                createdName = name
                createdUrl = url
            },
        )

        openAddForm()
        compose.onNodeWithTag(ServerProfilesTestTags.NameField)
            .performScrollTo()
            .performTextInput("Branch")
        compose.onNodeWithTag(ServerProfilesTestTags.UrlField)
            .performScrollTo()
            .performTextInput("https://branch.example/")
        compose.onNodeWithTag(ServerProfilesTestTags.SubmitAdd)
            .performScrollTo()
            .performClick()

        compose.runOnIdle {
            assertEquals("Branch", createdName)
            assertEquals("https://branch.example/", createdUrl)
        }
    }

    @Test
    fun callbackErrorKeepsTheAddFormAndItsDraft() {
        val error = mutableStateOf<String?>(null)
        var createCalls = 0
        compose.setContent {
            MeshCentralMobileTheme {
                ServerProfilesSheet(
                    profiles = listOf(alpha),
                    activeProfileId = alpha.id,
                    onActivate = {},
                    onCreate = { _, _ -> createCalls++ },
                    onRename = { _, _ -> },
                    onDelete = {},
                    onClose = {},
                    error = error.value,
                )
            }
        }
        compose.waitForIdle()

        openAddForm()
        compose.onNodeWithTag(ServerProfilesTestTags.NameField)
            .performScrollTo()
            .performTextInput("Branch")
        compose.onNodeWithTag(ServerProfilesTestTags.UrlField)
            .performScrollTo()
            .performTextInput("https://branch.example/")
        compose.onNodeWithTag(ServerProfilesTestTags.SubmitAdd)
            .performScrollTo()
            .performClick()

        compose.runOnIdle {
            assertEquals(1, createCalls)
            error.value = "The server rejected this address"
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ServerProfilesTestTags.ErrorBanner)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(ServerProfilesTestTags.AddForm)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(ServerProfilesTestTags.NameField).assertTextContains("Branch")
        compose.onNodeWithTag(ServerProfilesTestTags.UrlField)
            .assertTextContains("https://branch.example/")
    }

    @Test
    fun cancellingDeleteCallsNothing() {
        val deletedIds = mutableListOf<String>()
        show(listOf(alpha, beta), onDelete = { deletedIds += it })

        openMenu(alpha.id)
        compose.onNodeWithTag(ServerProfilesTestTags.delete(alpha.id)).performClick()
        compose.onNodeWithTag(ServerProfilesTestTags.CancelDelete).performClick()

        compose.runOnIdle { assertTrue(deletedIds.isEmpty()) }
        compose.onNodeWithTag(ServerProfilesTestTags.ConfirmDelete).assertDoesNotExist()
    }

    @Test
    fun confirmingDeleteCallsOnlyTheTargetId() {
        val deletedIds = mutableListOf<String>()
        show(listOf(alpha, beta), onDelete = { deletedIds += it })

        openMenu(alpha.id)
        compose.onNodeWithTag(ServerProfilesTestTags.delete(alpha.id)).performClick()
        compose.onNodeWithTag(ServerProfilesTestTags.ConfirmDelete).performClick()

        compose.runOnIdle { assertEquals(listOf("alpha"), deletedIds) }
    }

    @Test
    fun theLastProfileCannotBeDeleted() {
        show(listOf(alpha))

        openMenu(alpha.id)

        compose.onNodeWithTag(ServerProfilesTestTags.delete(alpha.id)).assertIsNotEnabled()
        compose.onNodeWithTag(ServerProfilesTestTags.ConfirmDelete).assertDoesNotExist()
    }

    @Test
    fun renameKeepsTheProfileId() {
        var renamedId: String? = null
        var renamedName: String? = null
        show(
            profiles = listOf(alpha),
            onRename = { id, name ->
                renamedId = id
                renamedName = name
            },
        )

        openMenu(alpha.id)
        compose.onNodeWithTag(ServerProfilesTestTags.rename(alpha.id)).performClick()
        compose.onNodeWithTag(ServerProfilesTestTags.RenameField)
            .assertTextContains("Alpha")
            .performTextClearance()
        compose.onNodeWithTag(ServerProfilesTestTags.RenameField).performTextInput("Alpha Renamed")
        compose.onNodeWithTag(ServerProfilesTestTags.ConfirmRename).performClick()

        compose.runOnIdle {
            assertEquals("alpha", renamedId)
            assertEquals("Alpha Renamed", renamedName)
        }
    }

    @Test
    fun emptyDraftShowsLocalErrorsWithoutCallingCreate() {
        var createCalls = 0
        show(
            profiles = listOf(alpha),
            onCreate = { _, _ -> createCalls++ },
        )

        openAddForm()
        compose.onNodeWithTag(ServerProfilesTestTags.SubmitAdd)
            .performScrollTo()
            .performClick()

        compose.runOnIdle { assertEquals(0, createCalls) }
        compose.onNodeWithText(text(R.string.profile_name_required))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.profile_url_required))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun show(
        profiles: List<ServerProfile>,
        onActivate: (String) -> Unit = {},
        onCreate: (String, String) -> Unit = { _, _ -> },
        onRename: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {},
    ) {
        compose.setContent {
            MeshCentralMobileTheme {
                ServerProfilesSheet(
                    profiles = profiles,
                    activeProfileId = profiles.first().id,
                    onActivate = onActivate,
                    onCreate = onCreate,
                    onRename = onRename,
                    onDelete = onDelete,
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun openAddForm() {
        compose.onNodeWithTag(ServerProfilesTestTags.AddAction)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
    }

    private fun openMenu(profileId: String) {
        compose.onNodeWithTag(ServerProfilesTestTags.menu(profileId)).performClick()
        compose.waitForIdle()
    }
}
