package ru.papajon.meshcentralmobile.integration

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.MeshDevice
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.notifications.NotificationContract
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.profiles.ServerProfilesTestTags
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/**
 * Real-Activity navigation traps around the one-shot launcher destination.
 *
 * Profiles A/B deliberately have no stored credentials and use reserved HTTPS example hosts, so
 * the only way to reach the sign-in screen is the pending destination itself. Each test snapshots
 * the global preference files (including the PIN) and restores them after clearing launcher work.
 */
class LauncherNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var settings: SettingsRepository
    private lateinit var workspace: ConnectionWorkspace
    private lateinit var originalPreferences: Map<String, Map<String, *>>
    private lateinit var originalProfileId: String
    private val createdProfiles = mutableListOf<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun captureOriginalState() = runBlocking {
        // Constructing the catalog first runs a possible legacy migration before the snapshot.
        settings = SettingsRepository(context)
        originalProfileId = settings.activeProfile.id
        originalPreferences = PREFERENCE_NAMES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        SecureVault(context).clearPin()
        settings.updateBiometricUnlock(false)
        // The app lock is process-wide: drop a lock left by an earlier test class.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            ConnectionWorkspace.get(context.applicationContext as android.app.Application).appLock.refreshLockRequirement()
        }
        settings.updateHideDevicesWhenLocked(false)
        // The update card is unrelated; a persisted decision keeps its dialog out of these flows.
        context.getSharedPreferences("app_update_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putLong("consent_version_code", 1L).commit()
        LauncherPrivacy.resetForTests()
        LauncherCoordinator.awaitIdle()
        LauncherCoordinator.clear(context)
        LauncherCoordinator.awaitIdle()
        workspace = withContext(Dispatchers.Main) {
            ConnectionWorkspace.get(context.applicationContext as Application)
        }
        withTimeout(15_000) { while (workspace.clearingSession) delay(25) }
        withContext(Dispatchers.Main) {
            workspace.cancelPendingDestinations()
            workspace.message = null
            workspace.showChatsRequested = false
            workspace.showTransfersRequested = false
        }
    }

    @After
    fun restoreOriginalState(): Unit = runBlocking {
        runCatching { scenario?.close() }
        scenario = null
        if (::workspace.isInitialized) {
            runCatching { withTimeout(15_000) { while (workspace.clearingSession) delay(25) } }
            runCatching {
                withContext(Dispatchers.Main) {
                    workspace.cancelPendingDestinations()
                    workspace.client.logout(true)
                    workspace.switchProfile(originalProfileId)
                }
            }
        }
        if (::settings.isInitialized) {
            runCatching {
                val current = SettingsRepository(context)
                createdProfiles.distinct()
                    .filter { id -> current.profilesState.value.any { it.id == id } }
                    .forEach { id ->
                        runCatching { SecureVault(context, id).clearCredentials() }
                        runCatching { SecureVault(context, id).clearLoginKey() }
                        runCatching { SecureVault(context, id).clearScopedSecret("notifications") }
                        current.removeProfile(id)
                    }
            }
        }
        runCatching {
            LauncherCoordinator.awaitIdle()
            LauncherCoordinator.clear(context)
            LauncherCoordinator.awaitIdle()
        }
        if (::originalPreferences.isInitialized) {
            PREFERENCE_NAMES.forEach { name ->
                restorePreferences(name, originalPreferences.getValue(name))
            }
        }
        LauncherPrivacy.resetForTests()
        runCatching { LauncherCoordinator.awaitIdle() }
    }

    @Test
    fun historyLaunchDoesNotReplayShortcutOrShare(): Unit = runBlocking {
        val (a, b, token) = prepareFixture()
        scenario = ActivityScenario.launch(DeviceShortcuts.intent(context, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            .putExtra(NotificationContract.EXTRA_PROFILE_ID, b.id))
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(a.id, workspace.profileId)
            assertNull(workspace.launcherDestination)
            assertNull(workspace.eventDestination)
            workspace.acceptShare(Intent("show-transfers").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
            workspace.acceptShare(Intent(ru.papajon.meshcentralmobile.background.ChatService.SHOW_CHATS)
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
            assertFalse(workspace.showTransfersRequested)
            assertFalse(workspace.showChatsRequested)
        }
    }

    @Test
    fun switchingProfileFromPendingLauncherCancelsTokenAndRecreationDoesNotReplay(): Unit = runBlocking {
        val (a, b, token) = prepareFixture()
        launch(token)
        awaitActive(b.id)
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(token, workspace.launcherDestination)
            assertTrue("The launcher token must be claimed once", workspace.launcherActivationAttempted)
        }

        compose.onNodeWithContentDescription(context.getString(R.string.login_edit_server)).performClick()
        compose.onNodeWithTag(ServerProfilesTestTags.Sheet).assertIsDisplayed()
        compose.onNodeWithTag(ServerProfilesTestTags.activate(a.id)).performClick()
        compose.waitUntil(15_000) {
            persistedActiveProfileId() == a.id && workspace.launcherDestination == null
        }
        compose.runOnIdle {
            assertEquals(a.id, workspace.profileId)
            assertFalse(workspace.launcherActivationAttempted)
            assertNull(workspace.eventDestination)
            assertNull(workspace.selectedDeviceId)
        }
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()

        requireNotNull(scenario).recreate()
        compose.waitForIdle()
        val activeAfterRecreate = persistedActiveProfileId()
        compose.runOnIdle {
            assertEquals(a.id, activeAfterRecreate)
            assertEquals(a.id, workspace.profileId)
            assertNull("Recreation must not resurrect profile B", workspace.launcherDestination)
            assertFalse(workspace.launcherActivationAttempted)
            assertNull(workspace.selectedDeviceId)
        }
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()
    }

    @Test
    fun continueWithoutSignInCancelsPendingLauncherAndRecreationDoesNotClaimIt() = runBlocking {
        val (_, b, token) = prepareFixture()
        launch(token)
        awaitActive(b.id)
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.login_continue_without)).performClick()
        compose.waitUntil(10_000) { workspace.launcherDestination == null }
        compose.runOnIdle {
            assertFalse(workspace.launcherActivationAttempted)
            assertNull(workspace.selectedDeviceId)
        }

        requireNotNull(scenario).recreate()
        compose.waitForIdle()
        val activeAfterRecreate = persistedActiveProfileId()
        compose.runOnIdle {
            assertEquals(b.id, activeAfterRecreate)
            assertEquals(b.id, workspace.profileId)
            assertNull(workspace.launcherDestination)
            assertFalse("Skipping sign-in must not replay the launch intent", workspace.launcherActivationAttempted)
            assertNull(workspace.selectedDeviceId)
        }
    }

    @Test
    fun eventDestinationReplacesPendingLauncherAndLauncherReplacesEventWithoutLooping() = runBlocking {
        val (a, b, token) = prepareFixture()
        launch(token)
        awaitActive(b.id)
        compose.onAllNodesWithText(context.getString(R.string.login_title)).onFirst().assertIsDisplayed()

        // A profile-targeted event replaces the pending launcher token, then activates profile A.
        val event = Intent(context, MainActivity::class.java)
            .putExtra(NotificationContract.EXTRA_PROFILE_ID, a.id)
        sendNewIntent(event) {
            assertNull("An event destination must replace a pending launcher", workspace.launcherDestination)
            assertEquals(a.id, workspace.eventDestination?.profileId)
            assertFalse(workspace.launcherActivationAttempted)
        }
        compose.waitUntil(15_000) {
            persistedActiveProfileId() == a.id && workspace.eventDestination == null
        }
        compose.runOnIdle {
            assertEquals(a.id, workspace.profileId)
            assertNull(workspace.launcherDestination)
            assertFalse(workspace.launcherActivationAttempted)
        }

        // The launcher token must replace (not queue behind) event state and claim exactly once.
        sendNewIntent(DeviceShortcuts.intent(context, token)) {
            assertNull("A launcher destination must replace an event", workspace.eventDestination)
            assertEquals(token, workspace.launcherDestination)
            assertFalse("The UI effect owns the one-time claim", workspace.launcherActivationAttempted)
        }
        compose.waitUntil(15_000) {
            persistedActiveProfileId() == b.id && workspace.launcherActivationAttempted
        }
        compose.runOnIdle {
            assertEquals(b.id, workspace.profileId)
            assertNull(workspace.eventDestination)
            assertEquals(token, workspace.launcherDestination)
        }

        // And an event can replace the still-pending launcher again without oscillation.
        sendNewIntent(event) {
            assertNull(workspace.launcherDestination)
            assertEquals(a.id, workspace.eventDestination?.profileId)
            assertFalse(workspace.launcherActivationAttempted)
        }
        compose.waitUntil(15_000) {
            persistedActiveProfileId() == a.id && workspace.eventDestination == null
        }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        assertEquals(a.id, persistedActiveProfileId())
        compose.runOnIdle {
            assertNull(workspace.launcherDestination)
            assertNull(workspace.eventDestination)
            assertNull(workspace.selectedDeviceId)
            assertFalse(workspace.launcherActivationAttempted)
        }
    }

    private suspend fun prepareFixture(): Triple<ServerProfile, ServerProfile, String> {
        val a = settings.createProfile(
            "Navigation A ${UUID.randomUUID()}",
            "https://example.invalid/navigation-a/",
            onboardingCompleted = true,
        )
        val b = settings.createProfile(
            "Navigation B ${UUID.randomUUID()}",
            "https://example.invalid/navigation-b/",
            onboardingCompleted = true,
        )
        createdProfiles += listOf(a.id, b.id)
        val node = "node//navigation-b"
        val token = LauncherStore(context).publish(
            b.id,
            LauncherStore.scope(b.id, b.serverUrl, USER_B),
            listOf(MeshDevice(node, "mesh//navigation", "Navigation B device", conn = 1)),
            setOf(node),
            listOf(node),
        ).devices.single().token
        settings.activateProfile(a.id)
        withContext(Dispatchers.Main) { workspace.switchProfile(a.id) }
        LauncherCoordinator.awaitIdle()
        return Triple(a, b, token)
    }

    private fun launch(token: String) {
        scenario = ActivityScenario.launch(DeviceShortcuts.intent(context, token))
    }

    private fun awaitActive(profileId: String) {
        compose.waitUntil(15_000) { persistedActiveProfileId() == profileId }
    }

    /**
     * The Activity owns a separate SettingsRepository instance. Read the persisted catalog instead
     * of the test instance's in-memory state, which does not observe another instance's writes.
     */
    private fun persistedActiveProfileId(): String =
        SettingsRepository(context).activeProfileIdState.value

    /**
     * ActivityScenario identifies its activity by the launch intent URI. The real callback mutates
     * activity.intent, so restore the tracked intent while still inside the same main-thread call.
     */
    private fun sendNewIntent(intent: Intent, verify: () -> Unit) {
        requireNotNull(scenario).onActivity { activity ->
            val trackedIntent = activity.intent
            instrumentation.callActivityOnNewIntent(activity, intent)
            activity.intent = trackedIntent
            verify()
        }
    }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, (value as Set<String>).toSet())
                }
            }
        }
        assertTrue("Could not restore $name", editor.commit())
    }

    private companion object {
        private const val USER_B = "user//navigation-b"
        private val PREFERENCE_NAMES = listOf(
            "app_settings",
            "secure_vault",
            "launcher_devices",
            "widget_auth_gate",
            "widget_status",
            "app_update_preferences",
            "notification_state",
        )
    }
}
