package ru.papajon.meshcentralmobile.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationStoreTest {
    @Test
    fun feedSettingsAndCursorAreScopedByProfileAndAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileId = "notif-store-${System.nanoTime()}"
        val now = 1_800_000_000_000L
        val server = "https://mesh.example/"
        try {
            val store = NotificationStore.open(context, profileId, server, "user/a")!!
            store.setEnabled(true)
            store.saveSettings(
                NotificationSettings(
                    kinds = setOf(NotificationEventKind.DEVICE_ALERT),
                    quietHours = QuietHours(true, 22, 7),
                ),
            )
            val item = NotificationFeedItem(
                "mce_1",
                NotificationEventKind.DEVICE_ALERT,
                "node/d1/a",
                null,
                now - 1000,
                now + 1000,
            )
            assertTrue(store.commitCheck(listOf(item), setOf("mce_1"), 41L, now))

            val reopened = NotificationStore.open(context, profileId, server, "user/a")!!
            assertTrue(reopened.isEnabled())
            assertEquals(41L, reopened.cursor())
            assertEquals(now, reopened.lastCheckAt())
            assertEquals(setOf("mce_1"), reopened.pending())
            assertEquals(listOf(item), reopened.feed())
            assertEquals(setOf(NotificationEventKind.DEVICE_ALERT), reopened.settings().kinds)

            val otherAccount = NotificationStore.open(context, profileId, server, "user/b")!!
            assertFalse(otherAccount.isEnabled())
            assertTrue(otherAccount.feed().isEmpty())
            assertEquals(0L, otherAccount.cursor())
        } finally {
            NotificationStore.clearProfile(context, profileId)
        }
        assertNull(NotificationStore.lastUserId(context, profileId))
    }
}
