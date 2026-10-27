package ru.papajon.meshcentralmobile.forward

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/**
 * Saved-rule management coverage: rules of a removed device, isolation between profiles, the
 * active-rule guard, the empty state and a profile switch while the dialog is open.
 */
class SavedPortForwardRulesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: PortForwardStore
    private lateinit var manager: PortForwardManager
    private lateinit var profileId: String
    private lateinit var otherProfileId: String

    @Before
    fun setUp() {
        profileId = "profile-${UUID.randomUUID()}"
        otherProfileId = "profile-${UUID.randomUUID()}"
        store = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        manager = PortForwardManager(
            allowed = { true },
            connect = { _, _, _, _ -> error("No client in the saved-rules fixture") },
        )
    }

    @After
    fun tearDown() {
        manager.close()
        store.deleteProfile(profileId)
        store.deleteProfile(otherProfileId)
    }

    @Test
    fun missingDeviceRuleCanBeDeletedAndFreshStoreConfirmsIt() {
        val savedRule = save(rule(name = "Old laptop RDP", kind = ForwardKind.RDP, remotePort = 3389))
        show(devices = emptyList())

        openDialog()
        compose.onNodeWithText(compose.activity.getString(R.string.pf_saved_device_missing)).assertIsDisplayed()
        compose.onNodeWithText(savedRule.nodeId).assertIsDisplayed()
        compose.onNodeWithTag(SavedPortForwardRulesTags.delete(savedRule.id)).performClick()

        val reopened = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnIdle {
            assertTrue(reopened.rules(profileId).isEmpty())
        }
        compose.onNodeWithTag(SavedPortForwardRulesTags.Empty).assertIsDisplayed()
    }

    @Test
    fun otherProfileRulesAreNotShownAndAreNotDamaged() {
        val other = save(
            rule(name = "Other profile rule", profile = otherProfileId, kind = ForwardKind.VNC, remotePort = 5900),
        )
        val mine = save(rule(name = "My profile rule", kind = ForwardKind.SSH, remotePort = 22))
        show()

        openDialog()
        compose.onNodeWithText(mine.name).assertIsDisplayed()
        compose.onNodeWithText(other.name).assertDoesNotExist()
        compose.onNodeWithTag(SavedPortForwardRulesTags.delete(mine.id)).performClick()

        val reopened = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnIdle {
            assertTrue(reopened.rules(profileId).isEmpty())
            assertEquals(1, reopened.rules(otherProfileId).size)
            assertEquals(other.name, reopened.rules(otherProfileId).single().name)
        }
    }

    @Test
    fun activeRuleShowsStatusAndDeleteIsDisabled() {
        val runningRule = save(rule(name = "Running tunnel", kind = ForwardKind.RDP, remotePort = 3389))
        assertTrue(manager.start(runningRule))
        show()

        openDialog()
        compose.onNodeWithText(compose.activity.getString(R.string.pf_saved_active)).assertIsDisplayed()
        compose.onNodeWithTag(SavedPortForwardRulesTags.status(runningRule.id)).assertIsDisplayed()
        compose.onNodeWithTag(SavedPortForwardRulesTags.delete(runningRule.id)).assertIsNotEnabled()

        compose.runOnUiThread { manager.stop(runningRule.id) }
        compose.waitForIdle()
        compose.onNodeWithTag(SavedPortForwardRulesTags.delete(runningRule.id)).assertIsEnabled()
    }

    @Test
    fun emptyProfileShowsEmptyStateInsideDialog() {
        show()

        openDialog()
        compose.onNodeWithTag(SavedPortForwardRulesTags.Empty).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.pf_saved_empty)).assertIsDisplayed()
    }

    @Test
    fun failedDeleteShowsLocalizedError() {
        val savedRule = save(rule(name = "Vanishing rule", kind = ForwardKind.CUSTOM, remotePort = 8080))
        show()
        openDialog()

        assertTrue(store.delete(savedRule.id))
        compose.onNodeWithTag(SavedPortForwardRulesTags.delete(savedRule.id)).performClick()

        compose.onNodeWithTag(SavedPortForwardRulesTags.Error).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.pf_saved_delete_failed)).assertIsDisplayed()
    }

    @Test
    fun deviceNameComesFromTheCurrentDeviceSnapshot() {
        val nodeId = "node/${UUID.randomUUID()}"
        save(rule(name = "Named device rule", nodeId = nodeId, kind = ForwardKind.HTTPS, remotePort = 443))
        show(devices = listOf(MeshDevice(nodeId = nodeId, meshId = "mesh/saved", name = "Office PC")))

        openDialog()
        compose.onNodeWithText("Office PC").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.pf_saved_device_missing)).assertDoesNotExist()
    }

    @Test
    fun profileSwitchClosesDialogAndFiltersRules() {
        val first = save(rule(name = "First profile rule", kind = ForwardKind.RDP, remotePort = 3389))
        val second = save(
            rule(name = "Second profile rule", profile = otherProfileId, kind = ForwardKind.SSH, remotePort = 22),
        )
        var currentProfile by mutableStateOf(profileId)
        compose.setContent {
            MeshCentralMobileTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    SavedPortForwardRules(
                        profileId = currentProfile,
                        store = store,
                        manager = manager,
                        devices = emptyList(),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        openDialog()
        compose.onNodeWithText(first.name).assertIsDisplayed()
        compose.onNodeWithText(second.name).assertDoesNotExist()

        compose.runOnUiThread { currentProfile = otherProfileId }
        compose.waitForIdle()
        compose.onNodeWithTag(SavedPortForwardRulesTags.Dialog).assertDoesNotExist()

        openDialog()
        compose.onNodeWithText(second.name).assertIsDisplayed()
        compose.onNodeWithText(first.name).assertDoesNotExist()
    }

    @Test
    fun capturesEnglishSavedRules() = capture(language = "en", fontScale = 1f, rtl = false)

    @Test
    fun capturesRussianLargeTextSavedRules() = capture(language = "ru", fontScale = 1.35f, rtl = false)

    @Test
    fun capturesArabicSavedRules() = capture(language = "ar", fontScale = 1f, rtl = true)

    private fun save(rule: PortForwardRule): PortForwardRule {
        assertTrue(store.save(rule))
        return rule
    }

    private fun rule(
        name: String,
        profile: String = profileId,
        nodeId: String = "node/${UUID.randomUUID()}",
        kind: ForwardKind = ForwardKind.CUSTOM,
        remotePort: Int = 8080,
    ): PortForwardRule = PortForwardRule(
        profileId = profile,
        nodeId = nodeId,
        name = name,
        kind = kind,
        remotePort = remotePort,
    )

    private fun show(devices: List<MeshDevice> = emptyList()) {
        compose.setContent {
            MeshCentralMobileTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    SavedPortForwardRules(
                        profileId = profileId,
                        store = store,
                        manager = manager,
                        devices = devices,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    private fun openDialog() {
        compose.onNodeWithTag(SavedPortForwardRulesTags.Manage).performClick()
        compose.onNodeWithTag(SavedPortForwardRulesTags.Dialog).assertIsDisplayed()
    }

    /** Artifact-only capture; the model reviews the PNGs, so the test asserts rendering only. */
    private fun capture(language: String, fontScale: Float, rtl: Boolean) {
        val activity = compose.activity
        val config = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = activity.createConfigurationContext(config)
        val namedNode = "node/${UUID.randomUUID()}"
        save(rule(name = "Office RDP", nodeId = namedNode, kind = ForwardKind.RDP, remotePort = 3389))
        save(rule(name = "Archive SSH", kind = ForwardKind.SSH, remotePort = 22))
        val devices = listOf(MeshDevice(nodeId = namedNode, meshId = "mesh/saved", name = "Office PC"))

        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides localized,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCentralMobileTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        SavedPortForwardRules(
                            profileId = profileId,
                            store = store,
                            manager = manager,
                            devices = devices,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SavedPortForwardRulesTags.Manage).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SavedPortForwardRulesTags.Dialog).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(activity.getExternalFilesDir(null), "saved-forward-evidence").apply { mkdirs() }
        File(folder, "saved-$language.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
