package ru.papajon.meshcentralmobile.mesh

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.MainActivity
import ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier
import ru.papajon.meshcentralmobile.notifications.NotificationContract
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.workspace.ConnectionWorkspace

/** No credentials or server needed: a pending notification waits at the normal sign-in boundary. */
class ChatNotificationNavigationTest {
    @Test fun newDeliverySurvivesSavedActivityStateButRotationDoesNotReplayIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsRepository(context)
        val previous = settings.activeProfile.id
        val profile = settings.createProfile("Chat navigation fixture", "https://127.0.0.1:1", true)
        val workspace = ConnectionWorkspace.get(context.applicationContext as Application)
        instrumentation.runOnMainSync { settings.activateProfile(profile.id); workspace.switchProfile(profile.id) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Preserve action/categories: ActivityScenario matches recreated Activities by
                // Intent.filterEquals; changing them makes it ignore the new Activity entirely.
                fun delivery(base: Intent, sequence: Long) = Intent(base)
                    .putExtra(NotificationContract.EXTRA_PROFILE_ID, profile.id)
                    .putExtra(NotificationContract.EXTRA_NODE_ID, "node//synthetic-chat")
                    .putExtra(ChatNotifier.EXTRA_SESSION, "node//synthetic-chat|Chat")
                    .putExtra(ChatNotifier.EXTRA_DELIVERY, sequence)
                // A new launch intent can accompany restored Activity state after destruction in background.
                scenario.onActivity { workspace.eventDestination = null; it.intent = delivery(it.intent, 100) }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals("node//synthetic-chat|Chat", workspace.eventDestination?.chatSession)
                    workspace.eventDestination = null
                }
                scenario.recreate()
                scenario.onActivity { assertNull("Rotation must not reopen a previously consumed notification", workspace.eventDestination) }
                scenario.onActivity { it.intent = delivery(it.intent, 101) }
                scenario.recreate()
                scenario.onActivity { assertEquals("node//synthetic-chat|Chat", workspace.eventDestination?.chatSession) }
                assertTrue("A notification must not invite or create a chat", workspace.sessions.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync {
                workspace.eventDestination = null
                workspace.closeAll()
                settings.activateProfile(previous)
                workspace.switchProfile(previous)
                settings.removeProfile(profile.id)
            }
        }
    }
}
