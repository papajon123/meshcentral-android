package ru.papajon.meshcentralmobile.update

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GitHubReleaseSourceTest {
    private val server = MockWebServer()
    private val reasons = mutableListOf<ReleaseRejection>()
    private var releases = JSONArray(fixture("releases.json"))
    private var info = JSONObject(fixture("release-info.json"))
    private var sums = "${"a".repeat(64)}  meshcentral-android-0.8.0.apk\n"
    private var status = 200
    private var metadataRedirect: String? = null
    private lateinit var source: GitHubReleaseSource

    @Before fun setup() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/repositories/") -> MockResponse().setResponseCode(status).setBody(releases.toString())
                request.path!!.endsWith("release-info.json") -> metadataRedirect?.let {
                    MockResponse().setResponseCode(302).addHeader("Location", it)
                } ?: MockResponse().setBody(info.toString())
                request.path!!.endsWith("SHA256SUMS") -> MockResponse().setBody(sums)
                else -> MockResponse().setResponseCode(404)
            }
        }
        // Production URL validation remains active; only socket routing is replaced by this test interceptor.
        val client = GitHubReleaseSource.newClient().newBuilder().addInterceptor { chain ->
            val original = chain.request()
            val local = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(local).build())
        }.build()
        source = GitHubReleaseSource(client, reasons::add)
    }

    @After fun teardown() { server.shutdown() }

    @Test fun selectsNewestPrereleaseWithMatchingAsset() {
        assertTrue(releases.getJSONObject(0).getBoolean("prerelease"))
        assertEquals(20L, source.latest()!!.versionCode)
        assertEquals(3, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/repositories/1428032345/releases?per_page=10", request.path)
        assertEquals("GET", request.method)
        assertTrue(request.getHeader("User-Agent")!!.contains("MeshPilot"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun skipsDraft() {
        releases.getJSONObject(0).put("draft", true)
        assertNull(source.latest())
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsShaMismatch() { sums = sums.replace('a', 'b'); reject(ReleaseRejection.Checksum) }
    @Test fun rejectsUnexpectedCertificate() { info.put("certificateSha256", "b".repeat(64)); reject(ReleaseRejection.Certificate) }
    @Test fun ignoresMissingApk() {
        releases.getJSONObject(0).getJSONArray("assets").remove(0)
        reject(ReleaseRejection.Assets)
        assertEquals(1, server.requestCount)
    }

    @Test fun comparesVersionCodeNotTagText() {
        val older = JSONObject(releases.getJSONObject(0).toString().replace("0.8.0", "9.0.0"))
        releases.put(older)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val old = request.path!!.contains("v9.0.0")
                return MockResponse().setBody(when {
                    request.path!!.startsWith("/repositories/") -> releases.toString()
                    request.path!!.endsWith("release-info.json") -> if (old) JSONObject(info.toString()).put("versionName", "9.0.0").put("versionCode", 19).toString() else info.toString()
                    else -> if (old) sums.replace("0.8.0", "9.0.0") else sums
                })
            }
        }
        assertEquals("0.8.0", source.latest()!!.versionName)
    }

    @Test fun handlesRateLimit403() {
        status = 403
        assertThrows(ReleaseRateLimitException::class.java) { source.latest() }
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsExternalAssetUrlBeforeRequest() {
        releases.getJSONObject(0).getJSONArray("assets").getJSONObject(1)
            .put("browser_download_url", "https://attacker.example/release-info.json")
        reject(ReleaseRejection.AssetUrl)
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsExternalRedirectBeforeRequest() {
        metadataRedirect = "https://attacker.example/metadata"
        reject(ReleaseRejection.AssetUrl)
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectsApkRedirectWithoutDownloadingIt() {
        metadataRedirect = "https://github.com/papajon123/meshcentral-android/releases/download/v0.8.0/meshcentral-android-0.8.0.apk"
        reject(ReleaseRejection.AssetUrl)
        assertEquals(2, server.requestCount)
    }

    @Test fun acceptsReleaseAfterRepositoryRename() {
        releases = JSONArray(releases.toString().replace("papajon123/meshcentral-android", "papajon123/meshpilot-android"))
        assertEquals(20L, source.latest()!!.versionCode)
        assertTrue(reasons.isEmpty())
    }

    @Test fun acceptsMeshPilotApkName() {
        releases = JSONArray(releases.toString().replace("meshcentral-android-0.8.0.apk", "meshpilot-android-0.8.0.apk"))
        sums = sums.replace("meshcentral-android-0.8.0.apk", "meshpilot-android-0.8.0.apk")
        assertEquals(20L, source.latest()!!.versionCode)
    }

    @Test fun rejectsBothApkNames() {
        val assets = releases.getJSONObject(0).getJSONArray("assets")
        assets.put(JSONObject(assets.getJSONObject(0).toString().replace("meshcentral-android-0.8.0.apk", "meshpilot-android-0.8.0.apk")))
        reject(ReleaseRejection.Assets)
    }

    @Test fun rejectsTheShortMeshPilotName() {
        releases = JSONArray(releases.toString().replace("papajon123/meshcentral-android", "papajon123/meshpilot"))
        reject(ReleaseRejection.AssetUrl)
    }

    @Test fun rejectsOtherRepositoryOfTheSameOwner() {
        releases = JSONArray(releases.toString().replace("papajon123/meshcentral-android", "papajon123/meshpilot-fork"))
        reject(ReleaseRejection.AssetUrl)
    }

    @Test fun rejectsDuplicateAssets() {
        val assets = releases.getJSONObject(0).getJSONArray("assets")
        assets.put(assets.getJSONObject(0))
        reject(ReleaseRejection.Assets)
    }

    @Test fun rejectsFractionalVersionCode() { info.put("versionCode", 20.5); reject(ReleaseRejection.Metadata) }
    @Test fun rejectsWrongPackage() { info.put("applicationId", "org.example.other"); reject(ReleaseRejection.Metadata) }
    @Test fun rejectsWrongVersionName() { info.put("versionName", "0.8.1"); reject(ReleaseRejection.Metadata) }
    @Test fun rejectsSizeMismatch() { info.put("sizeBytes", 2); reject(ReleaseRejection.Metadata) }
    @Test fun rejectsDuplicateChecksum() { sums += sums; reject(ReleaseRejection.Checksum) }
    @Test fun rejectsOversizedMetadata() { info.put("padding", "x".repeat(65 * 1024)); reject(ReleaseRejection.Metadata) }
    @Test fun rejectsInvalidTag() { releases.getJSONObject(0).put("tag_name", "v0.8.0/extra"); reject(ReleaseRejection.Tag) }
    @Test fun rejectsAmbiguousVersionCodes() { releases.put(releases.getJSONObject(0)); reject(ReleaseRejection.AmbiguousVersion) }
    @Test fun emptyListIsNotAFailure() { releases = JSONArray(); assertNull(source.latest()) }

    @Test fun ignoresInvalidReleaseAndKeepsVerifiedCandidate() {
        releases.put(JSONObject(releases.getJSONObject(0).toString()).put("tag_name", "not-a-version"))
        assertEquals(20L, source.latest()!!.versionCode)
        assertEquals(listOf(ReleaseRejection.Tag), reasons)
    }

    @Test fun stopsWhenForegroundRequestIsCancelled() {
        val cancelled = GitHubReleaseSource(checkActive = { throw java.util.concurrent.CancellationException() })
        // The cancellation hook must run before even the list request is sent.
        assertThrows(java.util.concurrent.CancellationException::class.java) { cancelled.latest() }
        assertEquals(0, server.requestCount)
    }

    private fun reject(reason: ReleaseRejection) {
        assertThrows(IllegalArgumentException::class.java) { source.latest() }
        assertTrue("Expected $reason, got $reasons", reason in reasons)
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/updates/$name")).readText()
}
