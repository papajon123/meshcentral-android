package ru.papajon.meshcentralmobile.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.papajon.meshcentralmobile.BuildConfig

data class UpdateUiState(
    val preferences: UpdatePreferences = UpdatePreferences(),
    val needsConsent: Boolean = true,
    val checking: Boolean = false,
    val notDue: Boolean = false,
    val candidate: UpdateCandidate? = null,
)

/** Activity-owned foreground checks, independent of all MeshCentral profiles and credentials. */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val store = UpdatePreferencesStore(application)
    private val installed = runCatching { PackageSignatureInspector(application).installed() }.getOrElse {
        InstalledRelease(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), "")
    }
    private val client = GitHubReleaseSource.newClient()
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init { refresh(store.load()) }

    fun consent(enabled: Boolean) {
        stop()
        val preferences = state.value.preferences.copy(enabled = enabled, consentVersionCode = installed.versionCode)
        store.save(preferences)
        if (!enabled) store.saveCandidate(null)
        refresh(preferences)
        if (enabled) check()
    }

    /** [manual] is the Settings button; automatic checks on start stay silent when not due. */
    fun check(manual: Boolean = false) {
        if (job?.isActive == true) return
        val preferences = state.value.preferences
        val now = System.currentTimeMillis()
        refresh(preferences)
        if (!UpdatePolicy.hasConsent(preferences)) return
        if (!UpdatePolicy.shouldCheck(preferences, now, manual)) {
            mutableState.value = state.value.copy(notDue = manual)
            return
        }
        // Persist before I/O, including interrupted attempts, to enforce the daily bound after restart.
        val attempted = preferences.copy(lastAttemptMillis = now, lastResult = UpdateResult.Network)
        store.save(attempted)
        mutableState.value = state.value.copy(preferences = attempted, checking = true, notDue = false)
        val requestGeneration = ++generation
        job = viewModelScope.launch {
            try {
                val decision = withContext(Dispatchers.IO) {
                    val requestContext = currentCoroutineContext()
                    val source = GitHubReleaseSource(client,
                        report = { reason -> Log.i("ReleaseCheck", "Ignored release: ${reason.name}") },
                        checkActive = { requestContext.ensureActive() },
                    )
                    UpdateChecker(source).check(installed, preferences, now, manual)
                }
                // A cached card can be postponed/skipped while the next check is running.
                // Apply the current preferences, never overwrite them with the request snapshot.
                val currentPreferences = state.value.preferences
                val currentDecision = if (decision is UpdateDecision.Available) {
                    UpdatePolicy.evaluate(decision.candidate, installed, currentPreferences, System.currentTimeMillis())
                } else decision
                val result = when (currentDecision) {
                    is UpdateDecision.Available -> UpdateResult.Available
                    UpdateDecision.UpToDate -> UpdateResult.UpToDate
                    UpdateDecision.Suppressed -> UpdateResult.Suppressed
                    is UpdateDecision.Failure -> when (currentDecision.problem) {
                        UpdateProblem.Network -> UpdateResult.Network
                        UpdateProblem.RateLimited -> UpdateResult.RateLimited
                        UpdateProblem.InvalidMetadata -> UpdateResult.InvalidMetadata
                        UpdateProblem.WrongSigner -> UpdateResult.WrongSigner
                    }
                    else -> UpdateResult.Never
                }
                if (decision is UpdateDecision.Available) store.saveCandidate(decision.candidate)
                else if (decision != UpdateDecision.Suppressed) store.saveCandidate(null)
                val completed = currentPreferences.copy(lastAttemptMillis = now, lastResult = result)
                store.save(completed)
                refresh(completed)
            } finally {
                if (generation == requestGeneration) mutableState.value = state.value.copy(checking = false)
            }
        }
    }

    fun later() = suppress(skip = false)
    fun skip() = suppress(skip = true)

    private fun suppress(skip: Boolean) {
        val candidate = state.value.candidate ?: return
        val preferences = if (skip) UpdatePolicy.skip(state.value.preferences, candidate.versionCode)
            else UpdatePolicy.later(state.value.preferences, candidate.versionCode, System.currentTimeMillis())
        val updated = preferences.copy(lastResult = UpdateResult.Suppressed)
        store.save(updated)
        refresh(updated)
        mutableState.value = state.value.copy(checking = job?.isActive == true)
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        client.dispatcher.cancelAll()
        mutableState.value = state.value.copy(checking = false)
    }

    private fun refresh(preferences: UpdatePreferences) {
        val candidate = store.candidate()?.takeIf {
            UpdatePolicy.hasConsent(preferences) &&
                UpdatePolicy.evaluate(it, installed, preferences, System.currentTimeMillis()) is UpdateDecision.Available
        }
        mutableState.value = UpdateUiState(preferences, UpdatePolicy.needsConsent(preferences), candidate = candidate)
    }

    override fun onCleared() { stop() }
}
