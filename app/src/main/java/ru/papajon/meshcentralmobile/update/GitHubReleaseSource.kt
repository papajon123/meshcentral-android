package ru.papajon.meshcentralmobile.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Only fixed, non-sensitive reason codes may leave the parser. Never log response bodies or exceptions. */
enum class ReleaseRejection { Tag, Assets, AssetUrl, Metadata, Checksum, Certificate, AmbiguousVersion }

class GitHubReleaseSource internal constructor(
    private val client: OkHttpClient = newClient(),
    private val report: (ReleaseRejection) -> Unit = {},
    private val checkActive: () -> Unit = {},
) : LatestReleaseSource {
    override fun latest(): UpdateCandidate? {
        val releases = try { JSONArray(read(API_URL, MAX_LIST_BYTES)) }
        catch (_: JSONException) { throw IllegalArgumentException("Invalid release list") }
        val candidates = mutableListOf<UpdateCandidate>()
        var rejected = false
        for (index in 0 until minOf(10, releases.length())) {
            checkActive()
            try {
                val release = releases.getJSONObject(index)
                if (release.getBoolean("draft")) continue
                candidates += candidate(release)
            } catch (error: RejectedRelease) {
                rejected = true
                report(error.reason)
            } catch (_: JSONException) {
                rejected = true
                report(ReleaseRejection.Metadata)
            }
        }
        if (candidates.groupBy { it.versionCode }.any { it.value.size > 1 }) {
            report(ReleaseRejection.AmbiguousVersion)
            throw IllegalArgumentException("Ambiguous release versions")
        }
        if (candidates.isEmpty() && rejected) throw IllegalArgumentException("No verifiable releases")
        return candidates.maxByOrNull { it.versionCode }
    }

    private fun candidate(release: JSONObject): UpdateCandidate {
        val tag = release.getString("tag_name")
        val version = tag.removePrefix("v")
        verify(tag.startsWith("v") && UpdatePolicy.VERSION.matches(version), ReleaseRejection.Tag)
        val assets = release.getJSONArray("assets")
        val apkName = "meshcentral-android-$version.apk"
        fun asset(name: String): JSONObject {
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name") == name }
            verify(matching.size == 1, ReleaseRejection.Assets)
            return matching.single().also {
                verify(it.getString("state") == "uploaded", ReleaseRejection.Assets)
                verify(it.getString("browser_download_url") == "$RELEASE_ORIGIN/releases/download/$tag/$name", ReleaseRejection.AssetUrl)
            }
        }
        val apk = asset(apkName)
        val infoAsset = asset("release-info.json")
        val sumsAsset = asset("SHA256SUMS")
        val info = JSONObject(read(infoAsset.getString("browser_download_url"), MAX_METADATA_BYTES))
        val sums = read(sumsAsset.getString("browser_download_url"), MAX_METADATA_BYTES)
        verify(info.getString("applicationId") == UpdatePolicy.PACKAGE_NAME && info.getString("versionName") == version, ReleaseRejection.Metadata)
        val code = positiveLong(info, "versionCode")
        verify(positiveLong(info, "sizeBytes") == positiveLong(apk, "size"), ReleaseRejection.Metadata)
        val sha = UpdatePolicy.normalizedFingerprint(info.getString("sha256"))
        verify(sha != null, ReleaseRejection.Checksum)
        val matchingSums = sums.lineSequence().filter { it.isNotBlank() }.map {
            SUM_LINE.matchEntire(it)?.destructured ?: throw RejectedRelease(ReleaseRejection.Checksum)
        }.filter { (_, name) -> name == apkName }.map { (hash, _) -> hash.lowercase() }.toList()
        verify(matchingSums.size == 1 && matchingSums.single() == sha, ReleaseRejection.Checksum)
        val signer = UpdatePolicy.normalizedFingerprint(info.getString("certificateSha256"))
        verify(signer == UpdatePolicy.CERTIFICATE_SHA256, ReleaseRejection.Certificate)
        return UpdateCandidate(UpdatePolicy.PACKAGE_NAME, code, version, requireNotNull(sha), requireNotNull(signer))
    }

    private fun positiveLong(json: JSONObject, name: String): Long {
        val value = json.get(name)
        verify(value is Int || value is Long, ReleaseRejection.Metadata)
        return (value as Number).toLong().also { verify(it > 0, ReleaseRejection.Metadata) }
    }

    private fun read(address: String, limit: Long): String {
        var url = address.toHttpUrl()
        repeat(4) {
            checkActive()
            verify(safeUrl(url), ReleaseRejection.AssetUrl)
            val request = Request.Builder().url(url).header("User-Agent", "MeshCentral-Android-UpdateCheck")
                .header("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream").build()
            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) throw ReleaseRateLimitException()
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let(url::resolve) ?: throw IOException("Missing metadata redirect")
                } else {
                    if (!response.isSuccessful) throw IOException("Release service unavailable")
                    val body = response.body ?: throw IOException("Missing release response")
                    verify(body.contentLength() <= limit, ReleaseRejection.Metadata)
                    val source = body.source()
                    source.request(limit + 1)
                    verify(source.buffer.size <= limit, ReleaseRejection.Metadata)
                    return source.readUtf8()
                }
            }
        }
        throw IOException("Too many metadata redirects")
    }

    private fun safeUrl(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && when (url.host) {
            "api.github.com" -> url.toString() == API_URL
            "github.com" -> url.query == null && url.encodedPath.startsWith("/papajon123/meshcentral-android/releases/download/v") &&
                (url.encodedPath.endsWith("/release-info.json") || url.encodedPath.endsWith("/SHA256SUMS"))
            "release-assets.githubusercontent.com" -> true // GitHub's signed asset redirect; never logged or persisted.
            else -> false
        }

    private fun verify(condition: Boolean, reason: ReleaseRejection) { if (!condition) throw RejectedRelease(reason) }
    private class RejectedRelease(val reason: ReleaseRejection) : IllegalArgumentException("Unverified release: ${reason.name}")

    companion object {
        const val API_URL = "https://api.github.com/repos/papajon123/meshcentral-android/releases?per_page=10"
        private const val RELEASE_ORIGIN = "https://github.com/papajon123/meshcentral-android"
        private const val MAX_LIST_BYTES = 1024 * 1024L
        private const val MAX_METADATA_BYTES = 64 * 1024L
        private val SUM_LINE = Regex("([a-fA-F0-9]{64}) [ *](.+)")
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
