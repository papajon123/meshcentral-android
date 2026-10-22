package ru.papajon.meshcentralmobile.notifications

import androidx.test.platform.app.InstrumentationRegistry
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.SettingsRepository

class NotificationDeliveryTest {
    @Test fun pendingIntentsForEqualEventIdsRemainIsolatedByProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = NotificationFeedItem("mce_1", NotificationEventKind.DEVICE_ONLINE, "node//fixture", null, 1, 2)
        val first = NotificationNotifier.contentIntent(context, "home", event, 7)
        val second = NotificationNotifier.contentIntent(context, "work", event, 7)
        try {
            assertNotEquals(first, second)
            assertEquals(first, NotificationNotifier.contentIntent(context, "home", event, 7))
        } finally { first.cancel(); second.cancel() }
    }
    @Test fun quietHoursDelayDeliveryAndPendingIntentSurvivesCursorAdvance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context)
        val profile = settings.createProfile("Notification fixture", "https://events.example.test")
        val now = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis
        val vault = SecureVault(context, profile.id)
        val token = NotificationToken("mcn_0123456789abcdef", "T".repeat(43), "/mobilecompanion/events", now + 86_400_000, "user//fixture")
        val store = requireNotNull(NotificationStore.open(context, profile.id, profile.serverUrl, token.userId))
        var delivered = 0
        var allowDelivery = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body("""{"items":[{"id":"mce_1","kind":"device-online","nodeid":"node//fixture","time":${now - 1000},"expiresAt":${now + 100000}}],"cursor":1,"resetCursor":false}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            vault.saveScopedSecret(NotificationContract.LABEL, profile.serverUrl, requireNotNull(NotificationCodec.encodeToken(token)))
            store.setEnabled(true)
            store.saveSettings(NotificationSettings(quietHours = QuietHours(true, 11, 13)))
            val poller = NotificationPoller(context, profile.id, { now }, NotificationHttpClient(http, { now }), { true }) { _, _, _ ->
                // Pending delivery must be durable before the system notification is attempted.
                assertEquals(setOf("mce_1"), store.pending())
                delivered++
                allowDelivery
            }
            assertEquals(NotificationPollResult.SUCCESS, poller.poll())
            assertEquals(0, delivered)
            assertEquals(1L, store.cursor())
            assertEquals(setOf("mce_1"), store.pending())
            store.saveSettings(NotificationSettings())
            poller.poll()
            assertEquals(1, delivered)
            assertEquals(setOf("mce_1"), store.pending())
            allowDelivery = true
            poller.poll()
            assertEquals(2, delivered)
            assertTrue(store.pending().isEmpty())
            poller.poll()
            assertEquals("Already delivered events must not notify twice", 2, delivered)
        } finally {
            NotificationController.disableLocal(context, profile.id)
            NotificationStore.clearProfile(context, profile.id)
            settings.removeProfile(profile.id)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
