package ru.papajon.meshcentralmobile.mesh

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.chat.ChatNotifier
import ru.papajon.meshcentralmobile.notifications.NotificationNotifier

class ChatNotificationTest {
    @Test fun genericPrivateNotificationIsScopedAndRemovedOnClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        NotificationNotifier.ensureChannel(context)
        val deadline = System.currentTimeMillis() + 3_000
        while (!NotificationNotifier.hasPermission(context) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = "node//synthetic-one|Chat"
        val second = "node//synthetic-two|Chat"
        try {
            assertTrue(ChatNotifier.post(context, "fixture-profile", "node//synthetic-one", first))
            assertTrue(ChatNotifier.post(context, "fixture-profile", "node//synthetic-two", second))
            val end = System.currentTimeMillis() + 3_000
            while (manager.activeNotifications.count { it.tag?.startsWith("chat:fixture-profile:") == true } != 2 && System.currentTimeMillis() < end) Thread.sleep(25)
            val notices = manager.activeNotifications.filter { it.tag?.startsWith("chat:fixture-profile:") == true }
            assertEquals(2, notices.size)
            notices.forEach {
                assertEquals(Notification.VISIBILITY_PRIVATE, it.notification.visibility)
                assertEquals(context.getString(R.string.app_name), it.notification.extras.getString(Notification.EXTRA_TITLE))
                assertTrue(it.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertEquals(context.getString(R.string.chat_unread, 1), it.notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(context.getString(R.string.notification_public_text), it.notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
            }
            assertNotEquals(notices[0].notification.contentIntent, notices[1].notification.contentIntent)
            ChatNotifier.cancel(context, "fixture-profile", first)
            val removed = System.currentTimeMillis() + 3_000
            while (manager.activeNotifications.any { it.tag == "chat:fixture-profile:$first" } && System.currentTimeMillis() < removed) Thread.sleep(25)
            assertFalse(manager.activeNotifications.any { it.tag == "chat:fixture-profile:$first" })
            assertTrue(manager.activeNotifications.any { it.tag == "chat:fixture-profile:$second" })
        } finally {
            ChatNotifier.cancel(context, "fixture-profile", first)
            ChatNotifier.cancel(context, "fixture-profile", second)
        }
    }
}
