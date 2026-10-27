package ru.papajon.meshcentralmobile.ui.screens.management

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.management.DeviceInvitation
import ru.papajon.meshcentralmobile.mesh.*
import ru.papajon.meshcentralmobile.model.ConnectionState
import ru.papajon.meshcentralmobile.ui.screens.DevicesContent
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme

class DeviceAdminUiTest {
    @Test fun unchangedLegacyDescriptionDoesNotPreventRenaming() {
        val original = device.copy(description = "Old\n" + "d".repeat(100))
        var saved = false
        show { MetadataDialog(original, false, false, true, {}) { name, description, _ ->
            assertEquals("Renamed", name); assertEquals(original.description, description); saved = true
        } }
        compose.onNodeWithTag("admin_name").performTextReplacement("Renamed")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("admin_review").performClick()
        compose.onNodeWithTag("admin_confirm").performClick()
        assertTrue(saved)
    }
    @Test fun endpointShowsHostAndPortWithoutInvitationCookie() {
        show { InviteDialogContent("Family", invitation, false, false, true, {}) {} }
        compose.onNodeWithTag("admin_endpoint").performScrollTo().assertTextEquals(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.admin_endpoint, "example.com:443"))
        assertFalse(compose.onNodeWithTag("admin_endpoint").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }.contains("synthetic"))
    }
    @Test fun oversizedNotesExplainLengthRatherThanPermissions() {
        show { NotesDialog(device.name, "n".repeat(16001), false, false, true, {}) {} }
        compose.onNodeWithTag("admin_review").assertIsNotEnabled()
        compose.onNodeWithTag("admin_notes_limit").performScrollTo().assertTextEquals(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.admin_notes_limit))
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.admin_unavailable)).assertDoesNotExist()
        compose.onNodeWithTag("admin_notes").performTextReplacement("Short note")
        compose.onNodeWithTag("admin_review").assertIsEnabled()
    }
    @Test fun dismissClearsOnlyTheInvitationClipboard() {
        val open = mutableStateOf(true)
        show { if (open.value) InviteDialogContent("Family", invitation, false, false, true, { open.value = false }) {} }
        compose.onNodeWithTag("admin_copy").performScrollTo().performClick()
        compose.runOnIdle { open.value = false }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertFalse(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == invitation.url)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("User text", "Keep this"))
            clearInvitationClipboard(compose.activity, invitation)
            assertEquals("Keep this", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun expirationClearsTheMatchingClipboard() {
        val current = mutableStateOf(invitation)
        show { InviteDialogContent("Family", current.value, false, false, true, {}) {} }
        compose.onNodeWithTag("admin_copy").performScrollTo().performClick()
        compose.runOnIdle { current.value = DeviceInvitation(invitation.url, 0) }
        compose.waitForIdle()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertFalse(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == invitation.url)
        }
    }
    @Test fun serverReasonAndExplicitReconnectAreAvailableAfterNotesFailure() {
        var reconnected = false
        show { NotesDialog(device.name, null, false, true, true, {}, "Access Denied", { reconnected = true }) {} }
        compose.onNodeWithTag("admin_failure_detail").performScrollTo().assertTextEquals("Access Denied")
        compose.onNodeWithTag("admin_reconnect").performScrollTo().performClick()
        assertTrue(reconnected)
        compose.onNodeWithTag("admin_review").assertIsNotEnabled()
    }
    @Test fun invitationCannotBeDismissedWhileCreationIsPending() {
        var dismissed = false
        show { InviteDialogContent("Family", null, true, false, true, { dismissed = true }) {} }
        compose.onNodeWithText(compose.activity.getString(ru.papajon.meshcentralmobile.R.string.admin_close)).assertIsNotEnabled()
        compose.onNodeWithTag("admin_create").assertIsNotEnabled()
        assertFalse(dismissed)
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = MeshDevice("node//example", "mesh//source", "Example PC", mtype = 2, rights = 133)
    private val invitation = DeviceInvitation("https://example.com/agentinvite?c=synthetic", System.currentTimeMillis() + 3_600_000)

    @Test fun editRequiresReviewAndConfirmation() {
        val changes = mutableListOf<List<String>>()
        show { MetadataDialog(device, false, false, true, {}) { a, b, c -> changes.add(listOf(a, b, c)) } }
        compose.onNodeWithTag("admin_name").performTextReplacement("Family PC")
        compose.onNodeWithTag("admin_description").performScrollTo().performTextReplacement("Living room")
        compose.onNodeWithTag("admin_tags").performScrollTo().performTextReplacement("family, home")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("admin_review").performClick()
        assertTrue(changes.isEmpty())
        compose.onNodeWithTag("admin_confirm").performClick()
        assertEquals(listOf(listOf("Family PC", "Living room", "family, home")), changes)
    }
    @Test fun revokedRightsDisableOpenConfirmationAndCancelDoesNotSend() {
        val allowed = mutableStateOf(true)
        var changes = 0
        show { MetadataDialog(device, false, false, allowed.value, {}) { _, _, _ -> changes++ } }
        compose.onNodeWithTag("admin_review").performClick()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithTag("admin_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("admin_cancel").performClick()
        assertEquals(0, changes)
    }
    @Test fun serverFailureRetainsDraftForCorrection() {
        val failed = mutableStateOf(false)
        show { MetadataDialog(device, false, failed.value, true, {}) { _, _, _ -> failed.value = true } }
        compose.onNodeWithTag("admin_name").performTextReplacement("Keep my draft")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("admin_review").performClick()
        compose.onNodeWithTag("admin_confirm").performClick()
        compose.onNodeWithTag("admin_cancel").performClick()
        compose.onNodeWithTag("admin_name").assertTextContains("Keep my draft")
    }
    @Test fun notesCannotOverwriteAnUnloadedValue() {
        show { NotesDialog(device.name, null, false, true, true, {}) { fail("Unloaded notes saved") } }
        compose.onNodeWithTag("admin_notes").assertIsNotEnabled()
        compose.onNodeWithTag("admin_review").assertIsNotEnabled()
    }
    @Test fun notesRequireConfirmationAndCanBeCleared() {
        val values = mutableListOf<String>()
        show { NotesDialog(device.name, "Old note", false, false, true, {}, onSave = values::add) }
        compose.onNodeWithTag("admin_notes").performTextClearance()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("admin_review").performClick()
        assertTrue(values.isEmpty())
        compose.onNodeWithTag("admin_confirm").performClick()
        assertEquals(listOf(""), values)
    }
    @Test fun moveRequiresDestinationThenConfirmationAndRechecksTargets() {
        val group = MeshGroup("mesh//target", "Relatives", mtype = 2, rights = 5)
        val targets = mutableStateOf(listOf(group))
        var moves = 0
        show { MoveDialog(device, targets.value, false, false, {}) { moves++ } }
        compose.onNodeWithTag("admin_review").assertIsNotEnabled()
        compose.onNodeWithTag("admin_target_${group.id}").performClick()
        compose.onNodeWithTag("admin_review").performClick()
        assertEquals(0, moves)
        compose.runOnIdle { targets.value = emptyList() }
        compose.onNodeWithTag("admin_confirm").assertIsNotEnabled()
    }
    @Test fun emptyGroupStillOffersInvitationAndLimitedAccountDoesNot() {
        val group = MeshGroup("mesh//empty", "Empty family group", mtype = 2, rights = 4)
        val state = mutableStateOf(MeshClientState(connectionState = ConnectionState.Authenticated, socketConnected = true,
            devicesCurrent = true, requiresSignIn = false, agentInstallAllowed = true, groups = listOf(group),
            serverCapabilities = MeshServerCapabilities(features = 0)))
        val selected = mutableListOf<String>()
        show { DevicesContent(state.value, emptyList(), { _, _ -> }, {}, {}, {}, {}, {}, onInvite = selected::add) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("admin_invite_${group.id}"))
        compose.onNodeWithTag("admin_invite_${group.id}").performClick()
        assertEquals(listOf(group.id), selected)
        compose.runOnIdle { state.value = state.value.copy(groups = listOf(group.copy(rights = 8))) }
        compose.onNodeWithTag("admin_invite_${group.id}").assertDoesNotExist()
    }
    @Test fun qrDecodesToExactInvitationIncludingLongDomainLink() {
        val url = "https://example.com/domain/agentinvite?c=" + "a@$".repeat(150)
        val bitmap = invitationQr(url)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(url, decoded.text)
        assertEquals("M", decoded.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
        bitmap.recycle()
    }
    @Test fun shareUsesSystemChooserAndPlainTextIntent() {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            show { InviteDialogContent("Family", invitation, false, false, true, {}) {} }
            compose.onNodeWithTag("admin_share").performScrollTo().performClick()
            Intents.intended(hasAction(Intent.ACTION_CHOOSER))
            @Suppress("DEPRECATION") val send = Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(invitation.url, send.getStringExtra(Intent.EXTRA_TEXT))
        } finally { Intents.release() }
    }
    @Test fun copyUsesSensitiveClipboardAndExpiredLinkCannotBeShared() {
        val current = mutableStateOf(invitation)
        show { InviteDialogContent("Family", current.value, false, false, true, {}) {} }
        compose.onNodeWithTag("admin_copy").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(invitation.url, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            current.value = DeviceInvitation(invitation.url, 0)
        }
        compose.onNodeWithTag("admin_share").assertDoesNotExist()
        compose.onNodeWithTag("admin_qr").assertDoesNotExist()
        compose.onNodeWithTag("admin_create").assertIsEnabled()
    }
    @Test fun largeFontRtlEditorsAndInvitationRemainReachable() {
        val screen = mutableStateOf(0)
        show("ar", 1.6f) {
            when (screen.value) {
                0 -> MetadataDialog(device, false, false, true, {}) { _, _, _ -> }
                1 -> InviteDialogContent("مجموعة العائلة", invitation, false, false, true, {}) {}
                else -> MoveDialog(device, listOf(MeshGroup("mesh//target", "مجموعة العائلة", mtype = 2, rights = 5)), false, false, {}) {}
            }
        }
        compose.onNodeWithTag("admin_tags").performScrollTo().assertIsDisplayed()
        capture("rtl-edit")
        compose.onNodeWithTag("admin_review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("admin_confirm").assertIsDisplayed()
        capture("rtl-confirm")
        compose.runOnIdle { screen.value = 1 }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("admin_qr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("admin_qr").performScrollTo().assertIsDisplayed()
        capture("rtl-qr")
        compose.onNodeWithTag("admin_share").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { screen.value = 2 }
        compose.onNodeWithTag("admin_target_mesh//target").performScrollTo().performClick()
        compose.onNodeWithTag("admin_review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("admin_confirm").assertIsDisplayed()
        capture("rtl-move")
    }
    @Test fun normalFontInvitationAndMetadataScreenshots() {
        val screen = mutableStateOf(0)
        show {
            if (screen.value == 2) MetadataDialog(device, false, false, true, {}) { _, _, _ -> }
            else InviteDialogContent("Family", invitation.takeIf { screen.value == 1 }, false, false, true, {}) {
                assertEquals(8, it); screen.value = 1
            }
        }
        compose.onNodeWithTag("admin_hours_8").performScrollTo().performClick()
        capture("invite-options")
        compose.onNodeWithTag("admin_create").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("admin_qr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("admin_qr").performScrollTo()
        capture("invite")
        compose.runOnIdle { screen.value = 2 }
        capture("edit")
    }

    private fun show(locale: String = "en", scale: Float = 1f, content: @Composable () -> Unit) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)); fontScale = scale }
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config, compose.activity.resources.displayMetrics)
        val context = compose.activity.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides androidx.compose.ui.unit.Density(context.resources.displayMetrics.density, scale),
                LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MeshCentralMobileTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        File(inst.targetContext.cacheDir, "admin-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
