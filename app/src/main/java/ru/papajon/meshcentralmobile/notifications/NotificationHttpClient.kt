package ru.papajon.meshcentralmobile.notifications

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import ru.papajon.meshcentralmobile.mesh.tls.ServerTls
import javax.net.ssl.X509TrustManager

class NotificationHttpException(
    val kind: Kind,
    val statusCode: Int = 0,
    val retryAfterMillis: Long = 0L,
) : IOException(kind.name) {
    enum class Kind {
        UNAUTHORIZED,
        RATE_LIMITED,
        REDIRECT,
        HTTP_ERROR,
        TOO_LARGE,
        INVALID_CONTENT_TYPE,
        EMPTY_BODY,
        NETWORK,
    }
}

/**
 * Bearer-only GET client. It never follows redirects, never sends cookies and reads at most
 * [NotificationEventParser.MAX_BODY_BYTES] from the response stream.
 *
 * The injected [client] seam keeps Android instrumented tests on a local MockWebServer; production
 * uses [forServer] with the current profile's endpoint and mandatory key pin.
 */
class NotificationHttpClient(
    client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCurrentPolicy: () -> Boolean = { true },
) : java.io.Closeable {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain ->
            // TLS may have blocked while the user replaced/forgot the key. This check runs
            // after the handshake but before HTTP headers (including the bearer) are written.
            if (!isCurrentPolicy()) throw IOException("Notification TLS policy changed")
            chain.proceed(chain.request())
        }.build()
    suspend fun fetch(url: HttpUrl, token: String, since: Long): NotificationEventPage =
        withContext(Dispatchers.IO) {
            val requestUrl = url.newBuilder()
                .addQueryParameter("since", since.coerceAtLeast(0L).toString())
                .addQueryParameter("limit", "100")
                .build()
            val request = Request.Builder()
                .url(requestUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .get()
                .build()
            val response = try {
                if (!isCurrentPolicy()) throw IOException("Notification TLS policy changed")
                client.newCall(request).execute()
            } catch (error: javax.net.ssl.SSLException) {
                throw error
            } catch (error: IOException) {
                throw NotificationHttpException(NotificationHttpException.Kind.NETWORK)
            }
            response.use {
                when (it.code) {
                    200 -> Unit
                    401 -> throw NotificationHttpException(NotificationHttpException.Kind.UNAUTHORIZED, 401)
                    429 -> throw NotificationHttpException(
                        NotificationHttpException.Kind.RATE_LIMITED,
                        429,
                        retryAfterMillis(it.header("Retry-After")),
                    )
                    in 300..399 -> throw NotificationHttpException(
                        NotificationHttpException.Kind.REDIRECT,
                        it.code,
                    )
                    else -> throw NotificationHttpException(
                        NotificationHttpException.Kind.HTTP_ERROR,
                        it.code,
                    )
                }
                val contentType = it.header("Content-Type").orEmpty()
                if (!contentType.startsWith("application/json", ignoreCase = true)) {
                    throw NotificationHttpException(NotificationHttpException.Kind.INVALID_CONTENT_TYPE, it.code)
                }
                val body = it.body ?: throw NotificationHttpException(
                    NotificationHttpException.Kind.EMPTY_BODY,
                    it.code,
                )
                if (body.contentLength() > NotificationEventParser.MAX_BODY_BYTES) {
                    throw NotificationHttpException(NotificationHttpException.Kind.TOO_LARGE, it.code)
                }
                val text = body.source().use { source -> readBounded(source) }
                NotificationEventParser.parse(text, since, clock())
            }
        }

    private fun readBounded(source: BufferedSource): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = source.read(buffer)
            if (read == -1) break
            total += read
            if (total > NotificationEventParser.MAX_BODY_BYTES) {
                throw NotificationHttpException(NotificationHttpException.Kind.TOO_LARGE, 200)
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun retryAfterMillis(value: String?): Long {
        val seconds = value?.trim()?.toLongOrNull() ?: return 60_000L
        return seconds.coerceIn(1L, 3_600L) * 1_000L
    }

    fun cancel() {
        client.dispatcher.cancelAll()
    }

    override fun close() {
        cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val TIMEOUT_SECONDS = 15L

        internal fun forServer(
            server: HttpUrl,
            pinnedSpkiSha256: String?,
            platform: X509TrustManager? = null,
            isCurrentPolicy: () -> Boolean = { true },
        ): NotificationHttpClient {
            val tls = if (platform == null) ServerTls(server, pinnedSpkiSha256)
                else ServerTls(server, pinnedSpkiSha256, platform = platform)
            return NotificationHttpClient(OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .apply { tls.applyTo(this) }
                .build(), isCurrentPolicy = isCurrentPolicy)
        }
    }
}
