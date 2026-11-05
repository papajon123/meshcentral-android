package ru.papajon.meshcentralmobile.update

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import ru.papajon.meshcentralmobile.BuildConfig

class PlayDistributionTest {
    @Test fun playNeverOffersOrChecksExternalUpdatesEvenAfterADirectInstall() {
        assumeFalse(BuildConfig.EXTERNAL_UPDATES_ENABLED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = UpdatePreferencesStore(app)
        val original = store.load()
        val remembered = original.copy(enabled = true, consentVersionCode = BuildConfig.VERSION_CODE.toLong(), lastAttemptMillis = 0)
        val models = ViewModelStore()
        try {
            store.save(remembered)
            instrumentation.runOnMainSync {
                val model = AppUpdateViewModel(app)
                models.put("play", model)
                assertFalse(model.state.value.preferences.enabled)
                assertFalse(model.state.value.needsConsent)
                assertNull(model.state.value.candidate)
                model.consent(true)
                model.check(manual = true)
                assertFalse(model.state.value.checking)
                assertEquals("No attempt may be scheduled or persisted", remembered, store.load())
                assertTrue(BuildConfig.SUPPORT_URL.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            store.save(original)
        }
    }
}
