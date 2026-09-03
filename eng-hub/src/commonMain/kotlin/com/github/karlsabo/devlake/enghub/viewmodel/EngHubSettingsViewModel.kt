package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.LoadedGitHubConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val TEXT_COMMIT_DEBOUNCE_MS = 750L
private const val MILLISECONDS_PER_SECOND = 1_000L
internal const val POLL_INTERVAL_ERROR = "Enter a positive whole number of seconds"
internal const val ORGANIZATION_ID_BLANK_ERROR = "Enter an organization ID"
internal const val ORGANIZATION_ID_DUPLICATE_ERROR = "Organization ID already exists"

class EngHubSettingsViewModel(
    engHubConfig: EngHubConfig,
    loadedGitHubConfig: LoadedGitHubConfig,
    directoryPicker: DirectoryPicker,
    filePicker: FilePicker,
    persistence: EngHubSettingsPersistence,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableUiState = MutableStateFlow(
        createEngHubSettingsUiState(
            engHubConfig,
            loadedGitHubConfig.config,
            loadedGitHubConfig.secret,
        ),
    )
    private val operationTracker = EngHubSettingsOperationTracker(coroutineScope)
    private val configPersistence = EngHubConfigSettingsPersistence(mutableUiState, persistence.updateConfig)

    val uiState: StateFlow<EngHubSettingsUiState> = mutableUiState.asStateFlow()
    internal val generalTextSettings = EngHubGeneralTextSettingsController(
        coroutineScope = coroutineScope,
        operationTracker = operationTracker,
        mutableUiState = mutableUiState,
        configPersistence = configPersistence,
    )
    internal val llmTemplateSettings = EngHubLlmTemplateSettingsController(
        coroutineScope = coroutineScope,
        mutableUiState = mutableUiState,
        configPersistence = configPersistence,
    )
    internal val gitHubTokenSettings = EngHubGitHubTokenController(
        coroutineScope = coroutineScope,
        operationTracker = operationTracker,
        mutableUiState = mutableUiState,
        filePicker = filePicker,
        persistence = persistence,
    )
    internal val directorySettings = EngHubDirectorySettingsController(
        directoryPicker = directoryPicker,
        coroutineScope = coroutineScope,
        operationTracker = operationTracker,
        mutableUiState = mutableUiState,
        configPersistence = configPersistence,
    )
    private val localRepositoryPathEditor = EngHubLocalRepositoryPathEditor(
        directoryPicker = directoryPicker,
        coroutineScope = coroutineScope,
        operationTracker = operationTracker,
        mutableUiState = mutableUiState,
        configPersistence = configPersistence,
        osFamily = persistence.repositoryPathOsFamily,
    )
    internal val setupCommandSettings = EngHubSetupCommandSettingsController(
        coroutineScope = coroutineScope,
        operationTracker = operationTracker,
        mutableUiState = mutableUiState,
        configPersistence = configPersistence,
        pathEditor = localRepositoryPathEditor,
    )
    internal val localRepositorySettings = EngHubLocalRepositorySettingsController(
        dependencies = EngHubLocalRepositorySettingsDependencies(
            coroutineScope = coroutineScope,
            operationTracker = operationTracker,
            configPersistence = configPersistence,
            pathEditor = localRepositoryPathEditor,
            osFamily = persistence.repositoryPathOsFamily,
        ),
        mutableUiState = mutableUiState,
        structureCallbacks = RepositoryStructureCallbacks(
            onRemoved = setupCommandSettings::onRepositoryRemoved,
            onInserted = setupCommandSettings::onRepositoryInserted,
        ),
    )

    init {
        persistence.committedConfigUpdates?.let { updates ->
            coroutineScope.launch {
                updates.collect { config ->
                    reconcileSettingsRepositories(
                        config,
                        mutableUiState,
                        localRepositoryPathEditor,
                        persistence.repositoryPathOsFamily,
                    )
                }
            }
        }
    }

    fun updateOrganizationIdDraft(organizationId: String) {
        mutableUiState.value = mutableUiState.value.copy(
            organizationIdDraft = organizationId,
            organizationIdError = null,
        )
    }

    fun addOrganizationId() {
        val state = mutableUiState.value
        val organizationId = state.organizationIdDraft.trim()
        val error = organizationId.validationError(state.organizationIds)
        if (error != null) {
            mutableUiState.value = state.copy(organizationIdError = error)
            return
        }

        mutableUiState.value = state.copy(
            organizationIds = state.organizationIds + organizationId,
            organizationIdDraft = "",
            organizationIdError = null,
        )
        val operationKey = configPersistence.newOperationKey("organization-add")
        operationTracker.launch {
            configPersistence.update(operationKey) { currentConfig ->
                currentConfig.copy(organizationIds = currentConfig.organizationIds + organizationId)
            }
        }
    }

    fun removeOrganizationId(index: Int) {
        val state = mutableUiState.value
        val organizationId = state.organizationIds.getOrNull(index) ?: return
        mutableUiState.value = state.copy(
            organizationIds = state.organizationIds.filterIndexed { rowIndex, _ -> rowIndex != index },
        )
        val operationKey = configPersistence.newOperationKey("organization-remove")
        operationTracker.launch {
            configPersistence.update(operationKey) { currentConfig ->
                currentConfig.copy(organizationIds = currentConfig.organizationIds - organizationId)
            }
        }
    }

    suspend fun flushPendingEdits() {
        operationTracker.awaitIdle()
        configPersistence.retryPendingUpdates()
        generalTextSettings.flushPendingEdits()
        llmTemplateSettings.flushPendingEdits()
        directorySettings.flushPendingEdits()
        localRepositorySettings.flushPendingEdits()
        setupCommandSettings.flushPendingEdits()
        gitHubTokenSettings.flushPendingEdit()
        operationTracker.awaitIdle()
        configPersistence.retryPendingUpdates()
    }
}

internal class EngHubGeneralTextSettingsController(
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val configPersistence: EngHubConfigSettingsPersistence,
) {
    private var authorCommitJob: Job? = null
    private var pendingAuthor: String? = null
    private var pollIntervalCommitJob: Job? = null
    private var pendingPollIntervalMs: Long? = null
    private var worktreePollIntervalCommitJob: Job? = null
    private var pendingWorktreePollIntervalMs: Long? = null
    private var setupShellCommitJob: Job? = null
    private var pendingSetupShell: String? = null

    fun updateGitHubAuthor(author: String) {
        mutableUiState.value = mutableUiState.value.copy(gitHubAuthor = author)
        authorCommitJob?.cancel()
        pendingAuthor = author
        authorCommitJob = coroutineScope.scheduleSettingsCommit { commitAuthor(author) }
    }

    fun updatePollIntervalSeconds(seconds: String) {
        pollIntervalCommitJob?.cancel()
        pollIntervalCommitJob = null
        pendingPollIntervalMs = null
        val intervalMs = seconds.toPollIntervalMillisecondsOrNull()
        mutableUiState.value = mutableUiState.value.copy(
            pollIntervalSeconds = seconds,
            pollIntervalError = if (intervalMs == null) POLL_INTERVAL_ERROR else null,
        )
        if (intervalMs == null) {
            operationTracker.launch { configPersistence.discard(POLL_INTERVAL_KEY) }
            return
        }
        pendingPollIntervalMs = intervalMs
        pollIntervalCommitJob = coroutineScope.scheduleSettingsCommit { commitPollInterval(intervalMs) }
    }

    fun updateWorktreePollIntervalSeconds(seconds: String) {
        worktreePollIntervalCommitJob?.cancel()
        worktreePollIntervalCommitJob = null
        pendingWorktreePollIntervalMs = null
        val intervalMs = seconds.toPollIntervalMillisecondsOrNull()
        mutableUiState.value = mutableUiState.value.copy(
            worktreePollIntervalSeconds = seconds,
            worktreePollIntervalError = if (intervalMs == null) POLL_INTERVAL_ERROR else null,
        )
        if (intervalMs == null) {
            operationTracker.launch { configPersistence.discard(WORKTREE_POLL_INTERVAL_KEY) }
            return
        }
        pendingWorktreePollIntervalMs = intervalMs
        worktreePollIntervalCommitJob = coroutineScope.scheduleSettingsCommit {
            commitWorktreePollInterval(intervalMs)
        }
    }

    fun updateSetupShell(shell: String) {
        mutableUiState.value = mutableUiState.value.copy(setupShell = shell)
        setupShellCommitJob?.cancel()
        pendingSetupShell = shell
        setupShellCommitJob = coroutineScope.scheduleSettingsCommit { commitSetupShell(shell) }
    }

    suspend fun flushPendingEdits() {
        authorCommitJob.cancelAndClear { pendingAuthor?.let { commitAuthor(it) } }
        pollIntervalCommitJob.cancelAndClear { pendingPollIntervalMs?.let { commitPollInterval(it) } }
        worktreePollIntervalCommitJob.cancelAndClear {
            pendingWorktreePollIntervalMs?.let { commitWorktreePollInterval(it) }
        }
        setupShellCommitJob.cancelAndClear { pendingSetupShell?.let { commitSetupShell(it) } }
    }

    private suspend fun commitAuthor(author: String) {
        val committed = configPersistence.update(AUTHOR_KEY) { currentConfig ->
            currentConfig.copy(gitHubAuthor = author)
        }
        if (committed && pendingAuthor == author) pendingAuthor = null
    }

    private suspend fun commitPollInterval(intervalMs: Long) {
        val committed = configPersistence.update(POLL_INTERVAL_KEY) { currentConfig ->
            currentConfig.copy(pollIntervalMs = intervalMs)
        }
        if (committed && pendingPollIntervalMs == intervalMs) pendingPollIntervalMs = null
    }

    private suspend fun commitWorktreePollInterval(intervalMs: Long) {
        val committed = configPersistence.update(WORKTREE_POLL_INTERVAL_KEY) { currentConfig ->
            currentConfig.copy(worktreePollIntervalMs = intervalMs)
        }
        if (committed && pendingWorktreePollIntervalMs == intervalMs) pendingWorktreePollIntervalMs = null
    }

    private suspend fun commitSetupShell(shell: String) {
        val committed = configPersistence.update(SETUP_SHELL_KEY) { currentConfig ->
            currentConfig.copy(setupShell = shell)
        }
        if (committed && pendingSetupShell == shell) pendingSetupShell = null
    }

    private companion object {
        const val AUTHOR_KEY = "github-author"
        const val POLL_INTERVAL_KEY = "poll-interval"
        const val WORKTREE_POLL_INTERVAL_KEY = "worktree-poll-interval"
        const val SETUP_SHELL_KEY = "setup-shell"
    }
}

internal fun CoroutineScope.scheduleSettingsCommit(commit: suspend () -> Unit): Job = launch {
    delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
    commit()
}

internal suspend fun Job?.cancelAndClear(commit: suspend () -> Unit) {
    this?.cancelAndJoin()
    commit()
}

internal fun CoroutineScope.launchAfterSettingsFlush(
    settingsViewModel: EngHubSettingsViewModel?,
    action: () -> Unit,
): Job = launch {
    var cancelled = false
    try {
        settingsViewModel?.flushPendingEdits()
    } catch (error: CancellationException) {
        cancelled = true
        throw error
    } finally {
        if (!cancelled) action()
    }
}

private fun String.validationError(existingOrganizationIds: List<String>): String? = when {
    isBlank() -> ORGANIZATION_ID_BLANK_ERROR

    existingOrganizationIds.any { existing -> existing.trim().equals(this, ignoreCase = true) } -> {
        ORGANIZATION_ID_DUPLICATE_ERROR
    }

    else -> null
}

private fun String.toPollIntervalMillisecondsOrNull(): Long? {
    val seconds = toLongOrNull()
    return if (seconds != null && seconds > 0 && seconds <= Long.MAX_VALUE / MILLISECONDS_PER_SECOND) {
        seconds * MILLISECONDS_PER_SECOND
    } else {
        null
    }
}
