package ru.papajon.meshcentralmobile.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.papajon.meshcentralmobile.R
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.ServerCertificateFailureKind
import ru.papajon.meshcentralmobile.mesh.tls.ServerTls
import ru.papajon.meshcentralmobile.mesh.tls.formatFingerprint
import ru.papajon.meshcentralmobile.mesh.tls.serverCertificateFailure
import ru.papajon.meshcentralmobile.mesh.tls.spkiSha256
import ru.papajon.meshcentralmobile.model.FavoriteLink
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.security.SecureVault
import ru.papajon.meshcentralmobile.settings.AppLockController
import ru.papajon.meshcentralmobile.settings.ServerCertificateApproval
import ru.papajon.meshcentralmobile.settings.SettingsRepository
import ru.papajon.meshcentralmobile.ui.screens.ServerCertificateDialog
import ru.papajon.meshcentralmobile.ui.theme.MeshCentralMobileTheme
import ru.papajon.meshcentralmobile.ui.theme.MeshThemeMode

/**
 * Regression coverage for the portable-pin -> expected-key handoff introduced by backup import.
 *
 * An imported pin is only a metadata expectation. It must never become trust on the first TLS
 * handshake, even when the endpoint and the expected fingerprint match, and only an explicit
 * [ServerCertificateApproval] may replace it with the actually observed key.
 */
class BackupRevisionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceNames = mutableListOf<String>()

    private data class Isolated(val name: String, val settings: SettingsRepository)

    private fun isolated(prefix: String): Isolated {
        val name = "backup-revision-$prefix-${UUID.randomUUID()}"
        preferenceNames += name
        preferenceNames += "$name-vault"
        return Isolated(name, SettingsRepository(context, name))
    }

    private fun backupWithPin(pin: String, url: String = "https://mesh.example.invalid/team/") = SettingsBackup(
        listOf(
            BackupProfile(
                ServerProfile("source", "Synthetic", url, onboardingCompleted = true, pinnedSpkiSha256 = pin),
                listOf(FavoriteLink("Workstation", "node//example")),
            ),
        ),
    )

    @After fun cleanUp() {
        preferenceNames.distinct().forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun importedPinIsPersistedPendingAndSnapshotReexportsItAsPortablePin() = runBlocking {
        val iso = isolated("pending")
        val pin = "a".repeat(64)
        assertEquals(1, SettingsBackupRepository(iso.settings).importConfirmed(backupWithPin(pin), applyPreferences = false))

        val immediate = iso.settings.profilesState.value.single { it.id != ProfileCatalog.DEFAULT_ID }
        assertNull(immediate.pinnedSpkiSha256)
        assertEquals(pin, immediate.expectedSpkiSha256)

        val reread = SettingsRepository(context, iso.name)
        val restored = reread.profilesState.value.single { it.id != ProfileCatalog.DEFAULT_ID }
        assertNull(restored.pinnedSpkiSha256)
        assertEquals(pin, restored.expectedSpkiSha256)

        val snapshot = SettingsBackupRepository(reread).snapshot(includeLoginKeys = false)
        val portable = snapshot.profiles.single().profile
        assertNull(portable.expectedSpkiSha256)
        assertEquals(pin, portable.pinnedSpkiSha256)
        val decoded = BackupCodec.decode(BackupCodec.encode(snapshot)).profiles.single().profile
        assertNull(decoded.expectedSpkiSha256)
        assertEquals(pin, decoded.pinnedSpkiSha256)
    }

    @Test fun duplicateDefaultIsSkippedByDefaultButExplicitlyAddedWhenRequested() = runBlocking {
        val iso = isolated("duplicates")
        val url = "https://mesh.example.invalid/team/"
        iso.settings.updateServerUrl(url)
        val backup = backupWithPin("a".repeat(64), url)
        val repository = SettingsBackupRepository(iso.settings)
        assertTrue(repository.isDuplicate(backup.profiles.single()))

        assertEquals(0, repository.importConfirmed(backup, applyPreferences = false))
        assertEquals(1, SettingsRepository(context, iso.name).profilesState.value.size)

        assertEquals(1, repository.importConfirmed(backup, applyPreferences = false, skipDuplicates = false))
        val reread = SettingsRepository(context, iso.name)
        assertEquals(2, reread.profilesState.value.size)
        assertEquals(url, reread.profilesState.value.single { it.id != ProfileCatalog.DEFAULT_ID }.serverUrl)
    }

    @Test fun importAppliesProfilesAndGlobalPreferencesIncludingAppearanceTogether() = runBlocking {
        val iso = isolated("globals")
        iso.settings.updateBlockScreenshots(false)
        iso.settings.updateLockTimeoutSeconds(60)
        val preferences = BackupPreferences(blockScreenshots = true, lockTimeoutSeconds = 15,
            stylusPrecisionEnabled = false, stylusHintSeen = true, themeMode = "Dark",
            dynamicColor = true, hideDevicesWhenLocked = true)
        val backup = backupWithPin("b".repeat(64)).copy(preferences = preferences)

        assertEquals(1, SettingsBackupRepository(iso.settings).importConfirmed(backup, applyPreferences = true))

        val reread = SettingsRepository(context, iso.name)
        val imported = reread.profilesState.value.single { it.id != ProfileCatalog.DEFAULT_ID }
        assertEquals("b".repeat(64), imported.expectedSpkiSha256)
        assertEquals(backup.profiles.single().favorites, reread.favoritesForProfile(imported))
        val settings = reread.getSettings()
        assertTrue(settings.blockScreenshots)
        assertEquals(15L, settings.lockTimeoutSeconds)
        assertFalse(settings.stylusPrecisionEnabled)
        assertTrue(settings.stylusHintSeen)
        assertTrue(settings.hideDevicesWhenLocked)
        val appearance = reread.readAppearance()
        assertEquals(MeshThemeMode.Dark, appearance.mode)
        assertTrue(appearance.dynamicColor)
    }

    @Test fun failedCommitRollsBackValuesProfileAndFavoriteKeysAndMemoryState() {
        val iso = isolated("rollback")
        val settings = iso.settings
        settings.updateBlockScreenshots(false)
        settings.updateLockTimeoutSeconds(60)
        settings.addFavorite("Existing", "node//existing")

        val prefs = context.getSharedPreferences(iso.name, Context.MODE_PRIVATE)
        val beforePrefs = prefs.all.toMap()
        val beforeProfiles = settings.profilesState.value
        val beforeActive = settings.activeProfileIdState.value
        val beforeSettings = settings.getSettings()
        val beforeFavorites = settings.favoritesState.value

        val newId = UUID.randomUUID().toString()
        val additions = listOf(
            ServerProfile(newId, "Imported", "https://commit.example.invalid/") to
                listOf(FavoriteLink("New", "node//new")),
        )
        val preferences = BackupPreferences(blockScreenshots = true, lockTimeoutSeconds = 15,
            stylusPrecisionEnabled = false, stylusHintSeen = true, themeMode = "Dark", dynamicColor = true,
            hideDevicesWhenLocked = true)
        var commitCalls = 0
        val callbacks = listOf<Pair<String, (SharedPreferences.Editor) -> Boolean>>(
            "returned-false" to { editor -> commitCalls++; editor.commit(); false },
            "thrown" to { editor -> commitCalls++; editor.commit(); throw IllegalStateException("synthetic commit failure") },
        )

        for ((label, commit) in callbacks) {
            val callsBefore = commitCalls
            assertThrows(IllegalStateException::class.java) {
                runBlocking { settings.appendImportedProfiles(additions, preferences, commit) }
            }
            assertEquals(label, callsBefore + 1, commitCalls)
            // The seam publishes the editor to the SharedPreferences memory map first, so the
            // rollback must restore this transaction's values and remove keys that did not exist.
            assertEquals(label, beforePrefs, prefs.all.toMap())
            assertEquals(label, beforeProfiles, settings.profilesState.value)
            assertEquals(label, beforeActive, settings.activeProfileIdState.value)
            assertEquals(label, beforeSettings, settings.getSettings())
            assertEquals(label, beforeFavorites, settings.favoritesState.value)
            assertFalse(label, prefs.all.keys.any { it.contains(newId) })
        }
    }

    @Test fun duplicateOnlyImportAppliesPreferencesWhenNothingIsAdded() = runBlocking {
        val iso = isolated("duplicate-preferences")
        val url = "https://mesh.example.invalid/team/"
        iso.settings.updateServerUrl(url)
        iso.settings.updateBlockScreenshots(false)
        iso.settings.updateLockTimeoutSeconds(60)
        val preferences = BackupPreferences(blockScreenshots = true, lockTimeoutSeconds = 15,
            themeMode = "Dark", dynamicColor = true)
        val backup = backupWithPin("c".repeat(64), url).copy(preferences = preferences)

        val repository = SettingsBackupRepository(iso.settings)
        assertTrue(repository.canImport(backup, skipDuplicates = true))
        assertEquals(0, repository.importConfirmed(backup, applyPreferences = true))

        val reread = SettingsRepository(context, iso.name)
        assertEquals(1, reread.profilesState.value.size)
        assertTrue(reread.getSettings().blockScreenshots)
        assertEquals(15L, reread.getSettings().lockTimeoutSeconds)
        assertEquals(MeshThemeMode.Dark, reread.readAppearance().mode)
        assertTrue(reread.readAppearance().dynamicColor)
    }

    @Test fun importedExpectedKeyIsRejectedBeforeHttpAndApprovalInstallsTheActualKey() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val actual = spkiSha256(certificate.certificate)
        // Both a matching and a mismatching backup pin are only confirmations, never trust.
        for (expected in listOf(actual, "00".repeat(32))) {
            val web = httpsServer(certificate)
            try {
                val url = web.url("/").newBuilder().host("localhost").build()
                assertImportedExpectedKeyRejected(web, url, expected, actual, certificate)
            } finally {
                web.close()
            }
        }
    }

    @Test fun expectedKeyRejectionUsesTheNormalDialogWithBackupTitle() {
        val expected = "cd".repeat(32)
        val failure = ServerCertificateFailure(
            kind = ServerCertificateFailureKind.ExpectedKeyConfirmation,
            spkiSha256 = "ab".repeat(32),
            subject = "CN=localhost",
            notBeforeMillis = 0L,
            notAfterMillis = 2_000_000_000_000L,
            serverAddress = "https://localhost:8443/",
            certificateSha256 = "ef".repeat(32),
            expectedSpkiSha256 = expected,
        )
        compose.setContent {
            MeshCentralMobileTheme { ServerCertificateDialog(failure, false, {}, {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.backup_key_approval_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.backup_key_mismatch)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(formatFingerprint(expected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tls_trust)).assertIsDisplayed()
    }

    private suspend fun assertImportedExpectedKeyRejected(
        web: MockWebServer,
        url: HttpUrl,
        expected: String,
        actual: String,
        certificate: HeldCertificate,
    ) {
        val iso = isolated("expected")
        val backup = backupWithPin(expected, url.toString())
        assertEquals(1, SettingsBackupRepository(iso.settings).importConfirmed(backup, applyPreferences = false))
        val imported = iso.settings.profilesState.value.single { it.id != ProfileCatalog.DEFAULT_ID }
        assertTrue(iso.settings.activateProfile(imported.id))
        assertNull(iso.settings.activeProfile.pinnedSpkiSha256)
        assertEquals(expected, iso.settings.activeProfile.expectedSpkiSha256)

        val rejected = client(url, pin = null, expected = expected, certificate = certificate)
        val failure = try {
            val observed = requireNotNull(tlsFailure(rejected, url))
            assertEquals(ServerCertificateFailureKind.ExpectedKeyConfirmation, observed.kind)
            assertEquals(expected, observed.expectedSpkiSha256)
            assertEquals(actual, observed.spkiSha256)
            assertEquals(url.toString(), observed.serverAddress)
            assertTrue(observed.canApprove)
            assertEquals("TLS rejection must precede the HTTP request", 0, web.requestCount)
            observed
        } finally {
            close(rejected)
        }

        val approval = ServerCertificateApproval(
            iso.settings,
            AppLockController(iso.settings, SecureVault(context, preferencesName = "${iso.name}-vault")),
        )
        assertTrue(approval.approve(failure))
        val reread = SettingsRepository(context, iso.name).activeProfile
        assertNull(reread.expectedSpkiSha256)
        assertEquals(actual, reread.pinnedSpkiSha256)

        val approved = client(url, pin = actual, expected = null, certificate = certificate)
        try {
            get(approved, url)
        } finally {
            close(approved)
        }
        assertEquals(1, web.requestCount)
    }

    private fun httpsServer(certificate: HeldCertificate): MockWebServer {
        val web = MockWebServer()
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        web.enqueue(MockResponse().setBody("fixture"))
        web.start(InetAddress.getByName("localhost"), 0)
        return web
    }

    private fun client(
        url: HttpUrl,
        pin: String?,
        expected: String?,
        certificate: HeldCertificate,
    ): OkHttpClient {
        // The endpoint is genuinely platform-trusted, so the expected-key rejection cannot be
        // just the default platform trust store refusing a private certificate.
        val platform = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
            .trustManager
        return OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .apply { ServerTls(url, pin, platform = platform, expectedSpkiSha256 = expected).applyTo(this) }
            .build()
    }

    private fun tlsFailure(client: OkHttpClient, url: HttpUrl): ServerCertificateFailure? {
        val error = runCatching { get(client, url) }.exceptionOrNull()
        assertTrue("Expected a TLS failure, got $error", error != null)
        return error?.serverCertificateFailure()
    }

    private fun get(client: OkHttpClient, url: HttpUrl) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    private fun close(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
