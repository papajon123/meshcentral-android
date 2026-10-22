package ru.papajon.meshcentralmobile.notifications

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationHttpClientTest {
    private val now = 1_800_000_000_000L
    private val body = """{"items":[{"id":"mce_1","kind":"device-online","time":${now - 1000},"expiresAt":${now + 1000}}],"cursor":8,"resetCursor":false}"""

    @Test
    fun sendsBearerAndOnlySinceAndLimitQuery() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(body),
            )
            val client = NotificationHttpClient(OkHttpClient(), clock = { now })
            val page = client.fetch(server.url("/mobilecompanion/events"), "T".repeat(43), 7L)
            assertEquals(8L, page.cursor)
            assertEquals(NotificationEventKind.DEVICE_ONLINE, page.items.single().kind)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer ${"T".repeat(43)}", request.getHeader("Authorization"))
            assertEquals("7", request.requestUrl!!.queryParameter("since"))
            assertEquals("100", request.requestUrl!!.queryParameter("limit"))
            assertNull(request.getHeader("Cookie"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun doesNotFollowRedirects() = runBlocking {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start()
        second.start()
        try {
            first.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", second.url("/mobilecompanion/events")),
            )
            val client = NotificationHttpClient(OkHttpClient(), clock = { now })
            try {
                client.fetch(first.url("/mobilecompanion/events"), "T".repeat(43), 0)
                fail("redirect must not be followed")
            } catch (error: NotificationHttpException) {
                assertEquals(NotificationHttpException.Kind.REDIRECT, error.kind)
            }
            assertNull(second.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }

    @Test
    fun maps401AndRejectsOversizedBodies() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val client = NotificationHttpClient(OkHttpClient(), clock = { now })
            try {
                client.fetch(server.url("/mobilecompanion/events"), "T".repeat(43), 0)
                fail("401 must be rejected")
            } catch (error: NotificationHttpException) {
                assertEquals(NotificationHttpException.Kind.UNAUTHORIZED, error.kind)
            }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("x".repeat(NotificationEventParser.MAX_BODY_BYTES + 1)),
            )
            try {
                client.fetch(server.url("/mobilecompanion/events"), "T".repeat(43), 0)
                fail("oversized body must be rejected")
            } catch (error: NotificationHttpException) {
                assertEquals(NotificationHttpException.Kind.TOO_LARGE, error.kind)
            }
            assertTrue(server.requestCount >= 2)
        } finally {
            server.shutdown()
        }
    }
}
