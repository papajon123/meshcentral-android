package ru.papajon.meshcentralmobile.forward

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

/** Production-composable coverage for the forwarding screen and its settings store. */
class PortForwardScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: PortForwardStore
    private lateinit var manager: PortForwardManager
    private lateinit var profileId: String
    private lateinit var nodeId: String
    private val createdElsewhere = mutableListOf<String>()

    @Before
    fun setUp() {
        profileId = "profile-${UUID.randomUUID()}"
        nodeId = "node/${UUID.randomUUID()}"
        store = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        manager = PortForwardManager(
            allowed = { true },
            connect = { _, _, _, _ -> error("unused") },
        )
    }

    @After
    fun tearDown() {
        manager.close()
        store.rules(profileId, nodeId).forEach { store.delete(it.id) }
        createdElsewhere.forEach { store.delete(it) }
    }

    @Test
    fun emptyDeviceShowsEmptyStateAndSecurityWarning() {
        show()

        compose.onNodeWithTag(PortForwardTestTags.Screen).assertIsDisplayed()
        compose.onNodeWithTag(PortForwardTestTags.Empty).assertIsDisplayed()
        compose.onNodeWithTag(PortForwardTestTags.Warning).assertIsDisplayed()
    }

    @Test
    fun addingARuleSavesItAndAFreshStoreReadsItBack() {
        show()

        compose.onNodeWithTag(PortForwardTestTags.Add).performClick()
        compose.onNodeWithTag(PortForwardTestTags.TemplateMenu).performClick()
        compose.onNodeWithTag(PortForwardTestTags.template("rdp")).performClick()
        compose.onNodeWithTag(PortForwardTestTags.Name).performTextClearance()
        compose.onNodeWithTag(PortForwardTestTags.Name).performTextInput("Home PC")
        compose.onNodeWithTag(PortForwardTestTags.Save).performClick()

        compose.onNodeWithText("Home PC").assertIsDisplayed()
        compose.onNodeWithTag(PortForwardTestTags.Dialog).assertDoesNotExist()

        val reopened = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnIdle {
            val rules = reopened.rules(profileId, nodeId)
            assertEquals(1, rules.size)
            assertEquals("Home PC", rules.single().name)
            assertEquals(ForwardKind.RDP, rules.single().kind)
            assertEquals(3389, rules.single().remotePort)
        }
    }

    @Test
    fun invalidRemotePortKeepsTheDialogOpenAndSavesNothing() {
        show()

        compose.onNodeWithTag(PortForwardTestTags.Add).performClick()
        compose.onNodeWithTag(PortForwardTestTags.TemplateMenu).performClick()
        compose.onNodeWithTag(PortForwardTestTags.template("rdp")).performClick()
        compose.onNodeWithTag(PortForwardTestTags.Name).performTextClearance()
        compose.onNodeWithTag(PortForwardTestTags.Name).performTextInput("Bad port")
        compose.onNodeWithTag(PortForwardTestTags.RemotePort).performTextClearance()
        compose.onNodeWithTag(PortForwardTestTags.RemotePort).performTextInput("70000")
        compose.onNodeWithTag(PortForwardTestTags.Save).performClick()

        val expected = compose.activity.getString(R.string.pf_form_remote_port_invalid)
        compose.onNodeWithTag(PortForwardTestTags.FormError).assertIsDisplayed()
        compose.onNodeWithText(expected).assertIsDisplayed()
        compose.onNodeWithTag(PortForwardTestTags.Dialog).assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.rules(profileId, nodeId).isEmpty()) }
    }

    @Test
    fun rulesFromAnotherProfileOrDeviceAreNotListed() {
        val otherNode = save(
            PortForwardRule(
                profileId = profileId,
                nodeId = "node/${UUID.randomUUID()}",
                name = "Other device",
                kind = ForwardKind.SSH,
                remotePort = 22,
            ),
        )
        val otherProfile = save(
            PortForwardRule(
                profileId = "profile-${UUID.randomUUID()}",
                nodeId = nodeId,
                name = "Other profile",
                kind = ForwardKind.VNC,
                remotePort = 5900,
            ),
        )
        createdElsewhere += otherNode.id
        createdElsewhere += otherProfile.id

        show()

        compose.onNodeWithTag(PortForwardTestTags.Empty).assertIsDisplayed()
        compose.onNodeWithText("Other device").assertDoesNotExist()
        compose.onNodeWithText("Other profile").assertDoesNotExist()
        compose.runOnIdle { assertTrue(store.rules(profileId, nodeId).isEmpty()) }
    }

    @Test
    fun deletingAStoppedRuleRemovesItFromTheStoreAndTheList() {
        val rule = save(
            PortForwardRule(
                profileId = profileId,
                nodeId = nodeId,
                name = "Delete me",
                kind = ForwardKind.HTTPS,
                remotePort = 443,
            ),
        )
        show()

        compose.onNodeWithTag(PortForwardTestTags.delete(rule.id)).performClick()

        compose.onNodeWithText("Delete me").assertDoesNotExist()
        compose.onNodeWithTag(PortForwardTestTags.Empty).assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.rules(profileId, nodeId).isEmpty()) }
    }

    @Test
    fun theTemplateMenuAppliesKindAndDefaultPort() {
        show()

        compose.onNodeWithTag(PortForwardTestTags.Add).performClick()
        compose.onNodeWithTag(PortForwardTestTags.TemplateMenu).performClick()
        listOf("rdp", "vnc", "ssh", "http80", "http8080", "https", "custom").forEach { id ->
            compose.onNodeWithTag(PortForwardTestTags.template(id)).assertExists()
        }
        compose.onNodeWithTag(PortForwardTestTags.template("http8080")).performClick()

        compose.onNodeWithTag(PortForwardTestTags.Name).assertTextContains("HTTP 8080")
        compose.onNodeWithTag(PortForwardTestTags.RemotePort).assertTextContains("8080")
        compose.onNodeWithTag(PortForwardTestTags.Save).performClick()

        compose.runOnIdle {
            val rule = store.rules(profileId, nodeId).single()
            assertEquals(ForwardKind.HTTP_ALT, rule.kind)
            assertEquals(8080, rule.remotePort)
        }
    }

    @Test
    fun activeRuleCopiesActualPortAndSwitchClosesListener() {
        val rule = save(PortForwardRule(profileId = profileId, nodeId = nodeId, name = "Copy endpoint",
            kind = ForwardKind.HTTP, remotePort = 80))
        assertTrue(manager.start(rule))
        val port = manager.states.value.single().localPort
        show()
        compose.onNodeWithTag(PortForwardTestTags.copy(rule.id)).performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("127.0.0.1:$port", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag(PortForwardTestTags.toggle(rule.id)).performClick()
        compose.runOnIdle { assertTrue(manager.states.value.none { it.running }) }
        compose.onNodeWithTag(PortForwardTestTags.address(rule.id)).assertDoesNotExist()
        java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
            assertTrue(it.isBound)
        }
    }

    @Test
    fun deletingProfileRemovesOnlyItsRulesAcrossDevices() {
        val one = save(PortForwardRule(profileId = profileId, nodeId = nodeId, name = "One", remotePort = 22))
        val two = save(one.copy(id = UUID.randomUUID().toString(), nodeId = "node//another"))
        val other = save(one.copy(id = UUID.randomUUID().toString(), profileId = "other-$profileId"))
        createdElsewhere += listOf(two.id, other.id)
        assertTrue(store.deleteProfile(profileId))
        val fresh = PortForwardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(fresh.rules(profileId, nodeId).isEmpty())
        assertTrue(fresh.rules(profileId, two.nodeId).isEmpty())
        assertEquals(listOf(other), fresh.rules(other.profileId, nodeId))
    }

    @Test
    fun deviceStopButtonLeavesOtherDeviceRunning() {
        val one = save(PortForwardRule(profileId = profileId, nodeId = nodeId, name = "Current", remotePort = 22))
        val other = save(one.copy(id = UUID.randomUUID().toString(), nodeId = "node//other", name = "Other device"))
        createdElsewhere += other.id
        assertTrue(manager.start(one)); assertTrue(manager.start(other))
        show()
        compose.onNodeWithTag(PortForwardTestTags.StopAll).performClick()
        compose.runOnIdle {
            assertTrue(!manager.states.value.single { it.rule.id == one.id }.running)
            assertTrue(manager.states.value.single { it.rule.id == other.id }.running)
        }
    }

    private fun save(rule: PortForwardRule): PortForwardRule {
        assertTrue(store.save(rule))
        return rule
    }

    private fun show() {
        compose.setContent {
            MeshCentralMobileTheme {
                PortForwardScreen(
                    profileId = profileId,
                    nodeId = nodeId,
                    manager = manager,
                    store = store,
                    onStart = {},
                    onBack = {},
                )
            }
        }
    }
}
