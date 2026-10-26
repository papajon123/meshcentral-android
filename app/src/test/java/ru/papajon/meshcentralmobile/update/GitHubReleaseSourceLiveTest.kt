package ru.papajon.meshcentralmobile.update

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only; no network access in the normal test suite. Never downloads an APK. */
class GitHubReleaseSourceLiveTest {
    @Test fun findsPublicReleaseWithoutAuthentication() {
        assumeTrue(System.getenv("MESH_UPDATE_LIVE_CHECK") == "1")
        val release = try { GitHubReleaseSource().latest() } catch (error: Exception) {
            throw AssertionError("Live release check failed: ${error.javaClass.simpleName}")
        }
        assertNotNull(release)
        assertEquals("0.7.3", release!!.versionName)
        assertEquals(18L, release.versionCode)
        println("Verified public release v${release.versionName}, versionCode ${release.versionCode}")
    }
}
