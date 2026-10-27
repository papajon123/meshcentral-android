package ru.papajon.meshcentralmobile.backup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papajon.meshcentralmobile.model.ProfileCatalog
import ru.papajon.meshcentralmobile.model.ServerProfile
import ru.papajon.meshcentralmobile.settings.SettingsRepository

/**
 * Second-review regressions for backup preview: endpoint normalization must also catch repeats
 * inside a single file, while an explicit skipDuplicates=false keeps separate accounts.
 */
class BackupSecondReviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceNames = mutableListOf<String>()

    private fun repository(): SettingsRepository {
        val name = "backup-second-review-${UUID.randomUUID()}"
        preferenceNames += name
        return SettingsRepository(context, name)
    }

    private fun entry(id: String, url: String) = BackupProfile(
        ServerProfile(id, "Synthetic $id", url, onboardingCompleted = true),
    )

    private fun backup(vararg profiles: BackupProfile) = SettingsBackup(profiles.toList())

    @After fun cleanUp() {
        preferenceNames.distinct().forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun duplicateIdsNormalizeTrailingSlashesAndHostCaseAgainstExistingProfiles() {
        val settings = repository()
        settings.createProfile("Existing", "https://mesh.example.invalid/team/")
        val repository = SettingsBackupRepository(settings)
        val pending = backup(
            entry("trailing", "https://mesh.example.invalid/team"),
            entry("host-case", "https://MESH.example.invalid/team/"),
            entry("other", "https://other.example.invalid/team/"),
        )

        assertEquals(setOf("trailing", "host-case"), repository.duplicateIds(pending))
        assertTrue(repository.isDuplicate(pending.profiles.first()))
        assertEquals(
            listOf("other"),
            repository.selectedProfiles(pending, skipDuplicates = true).map { it.profile.id },
        )
        assertEquals(
            listOf("trailing", "host-case", "other"),
            repository.selectedProfiles(pending, skipDuplicates = false).map { it.profile.id },
        )
    }

    @Test fun duplicateIdsMarkExistingEndpointsAndLaterRepeatsInsideTheFile() {
        val settings = repository()
        settings.createProfile("Existing", "https://existing.example.invalid/")
        val repository = SettingsBackupRepository(settings)
        val pending = backup(
            entry("existing-slash", "https://existing.example.invalid"),
            entry("existing-again", "https://existing.example.invalid/"),
            entry("first-new", "https://new.example.invalid/team/"),
            entry("second-new", "https://NEW.example.invalid/team"),
            entry("another", "https://another.example.invalid/"),
        )

        assertEquals(
            setOf("existing-slash", "existing-again", "second-new"),
            repository.duplicateIds(pending),
        )
        assertEquals(
            listOf("first-new", "another"),
            repository.selectedProfiles(pending, skipDuplicates = true).map { it.profile.id },
        )
        assertEquals(
            pending.profiles,
            repository.selectedProfiles(pending, skipDuplicates = false),
        )
    }

    @Test fun capacityCountsOnlyTheSelectedProfilesWhenDuplicatesAreSkipped() {
        val settings = repository()
        repeat(ProfileCatalog.MAX_PROFILES - 2) { index ->
            settings.createProfile("Synthetic $index", "https://capacity-$index.example.invalid/")
        }
        assertEquals(ProfileCatalog.MAX_PROFILES - 1, settings.profilesState.value.size)
        val pending = backup(
            entry("first", "https://capacity-new.example.invalid/"),
            entry("second", "https://capacity-new.example.invalid"),
        )
        val repository = SettingsBackupRepository(settings)

        assertTrue(repository.canImport(pending, skipDuplicates = true))
        assertFalse(repository.canImport(pending, skipDuplicates = false))
    }
}
