package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.MaskedTokenUiState
import com.github.karlsabo.gitlab.config.GitLabApiRestConfig
import com.github.karlsabo.gitlab.config.GitLabSecret
import com.github.karlsabo.gitlab.config.GitLabSecretWriteException
import com.github.karlsabo.gitlab.normalizeGitLabInstanceUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.milliseconds

internal const val GITLAB_INSTANCE_URL_ERROR = "Enter an HTTPS GitLab instance URL"
internal const val GITLAB_SECRET_PATH_REQUIRED_ERROR = "Enter a GitLab secret file path"
internal const val GITLAB_SECRET_PATH_ERROR =
    "Choose a GitLab secret path outside the protected configuration files"
internal const val GITLAB_SECRET_SAVE_ERROR =
    "Could not save the GitLab token securely. Check the secret path and file permissions."
internal const val GITLAB_ACCESS_NOT_READY_ERROR = "Save a valid GitLab instance URL and token first"
internal const val GITLAB_VALIDATION_FAILURE =
    "Could not validate the GitLab account. Check the instance URL and token."
private const val GITLAB_SECRET_PICKER_TITLE = "Choose GitLab secret file"
private const val GITLAB_CONFIG_OPERATION_PREFIX = "gitlab-access"

internal class EngHubGitLabSettingsController(
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val filePicker: FilePicker,
    private val persistence: EngHubSettingsPersistence,
    configPersistence: EngHubConfigSettingsPersistence,
) {
    private val committer = GitLabAccessCommitter(
        mutableUiState = mutableUiState,
        persistence = persistence,
        configPersistence = configPersistence,
    )
    private var commitJob: Job? = null
    private var hasPendingEdit = false

    fun updateInstanceUrl(url: String) {
        mutableUiState.value = mutableUiState.value.copy(
            gitLabInstanceUrl = url,
            gitLabInstanceUrlError = null,
            gitLabValidationMessage = null,
        )
        scheduleCommit()
    }

    fun updateSecretPath(path: String) {
        mutableUiState.value = mutableUiState.value.copy(
            gitLabTokenPath = path,
            gitLabTokenPathError = null,
            gitLabValidationMessage = null,
        )
        scheduleCommit()
    }

    fun chooseSecretPath() {
        operationTracker.launch {
            val path = filePicker.pickFilePath(GITLAB_SECRET_PICKER_TITLE) ?: return@launch
            commitJob?.cancelAndJoin()
            commitJob = null
            hasPendingEdit = true
            mutableUiState.value = mutableUiState.value.copy(gitLabTokenPath = path, gitLabTokenPathError = null)
            commitCurrentDraft()
        }
    }

    fun updateToken(token: String) {
        commitJob?.cancel()
        val tokenState = mutableUiState.value.gitLabToken
        mutableUiState.value = mutableUiState.value.copy(
            gitLabToken = if (token == tokenState.value) {
                tokenState.withCommittedToken(token)
            } else {
                tokenState.withDraft(token)
            },
            gitLabTokenError = null,
            gitLabValidationMessage = null,
        )
        scheduleCommit()
    }

    fun validateAccess() {
        operationTracker.launch {
            commitJob?.cancelAndJoin()
            commitJob = null
            commitCurrentDraft()
            val apiConfig = currentApiConfig()
            if (apiConfig == null) {
                mutableUiState.value = mutableUiState.value.copy(
                    gitLabValidationMessage = GITLAB_ACCESS_NOT_READY_ERROR,
                )
                return@launch
            }
            val result = runCatching { persistence.gitLab.validateAccess(apiConfig) }.rethrowCancellation()
            mutableUiState.value = mutableUiState.value.copy(
                gitLabValidationMessage = result.fold(
                    onSuccess = { username -> "Connected as @$username" },
                    onFailure = { GITLAB_VALIDATION_FAILURE },
                ),
            )
        }
    }

    suspend fun flushPendingEdit() {
        commitJob?.cancelAndJoin()
        commitJob = null
        commitCurrentDraft()
    }

    private fun scheduleCommit() {
        commitJob?.cancel()
        hasPendingEdit = true
        commitJob = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            commitCurrentDraft()
        }
    }

    private suspend fun commitCurrentDraft() {
        if (!hasPendingEdit) return
        val draft = mutableUiState.value.gitLabDraft()
        if (!mutableUiState.validateGitLabDraft(draft)) return
        hasPendingEdit = committer.commit(draft)
    }

    private fun currentApiConfig(): GitLabApiRestConfig? {
        val state = mutableUiState.value
        val normalizedUrl = normalizeGitLabInstanceUrl(state.gitLabInstanceUrl)
        val token = state.gitLabToken.value
        val isReady = normalizedUrl != null && state.gitLabTokenPath.isNotBlank() && token.isNotBlank()
        return if (isReady) GitLabApiRestConfig(requireNotNull(normalizedUrl), token) else null
    }
}

/**
 * Persists one GitLab access draft. It owns the committed values because a debounced draft, or a failed
 * configuration write, can leave the UI ahead of what was stored.
 */
private class GitLabAccessCommitter(
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val persistence: EngHubSettingsPersistence,
    private val configPersistence: EngHubConfigSettingsPersistence,
) {
    private val writeMutex = Mutex()
    private val configOperationKey = configPersistence.newOperationKey(GITLAB_CONFIG_OPERATION_PREFIX)
    private var committedSecretPath = mutableUiState.value.gitLabTokenPath
    private var committedInstanceUrl = mutableUiState.value.gitLabInstanceUrl
    private var committedToken = mutableUiState.value.gitLabToken.value

    suspend fun commit(draft: GitLabAccessDraft): Boolean = writeMutex.withLock { persist(draft) }

    private suspend fun persist(draft: GitLabAccessDraft): Boolean {
        val saved = saveSecretIfChanged(draft) && saveConfigIfChanged(draft)
        if (saved) markCommitted(draft)
        return !saved
    }

    private suspend fun saveSecretIfChanged(draft: GitLabAccessDraft): Boolean {
        if (draft.tokenPath == committedSecretPath && draft.token == committedToken) return true
        return saveValidatedSecret(Path(draft.tokenPath), draft)
    }

    private suspend fun saveValidatedSecret(secretPath: Path, draft: GitLabAccessDraft): Boolean = try {
        persistence.gitLab.validateSecretPath(secretPath)
        try {
            persistence.gitLab.saveSecret(secretPath, GitLabSecret(draft.token))
            true
        } catch (_: GitLabSecretWriteException) {
            markErrorIfCurrent(draft) { state -> state.copy(gitLabTokenError = GITLAB_SECRET_SAVE_ERROR) }
            false
        }
    } catch (_: GitLabSecretWriteException) {
        markErrorIfCurrent(draft) { state -> state.copy(gitLabTokenPathError = GITLAB_SECRET_PATH_ERROR) }
        false
    }

    private suspend fun saveConfigIfChanged(draft: GitLabAccessDraft): Boolean {
        val normalizedUrl = draft.normalizedInstanceUrl.orEmpty()
        if (normalizedUrl == committedInstanceUrl && draft.tokenPath == committedSecretPath) return true
        return configPersistence.update(configOperationKey) { config ->
            config.copy(gitLabInstanceUrl = normalizedUrl, gitLabTokenPath = draft.tokenPath)
        }
    }

    private fun markCommitted(draft: GitLabAccessDraft) {
        val currentState = mutableUiState.value
        val remainingDraftToken = currentState.gitLabToken.fieldValue.takeUnless {
            currentState.matchesGitLabDraft(draft)
        }
        committedSecretPath = draft.tokenPath
        committedInstanceUrl = draft.normalizedInstanceUrl.orEmpty()
        committedToken = draft.token
        val accessReady = committedInstanceUrl.isNotBlank() &&
            draft.tokenPath.isNotBlank() &&
            draft.token.isNotBlank()
        mutableUiState.value = currentState.copy(
            gitLabInstanceUrl = committedInstanceUrl,
            gitLabTokenPath = draft.tokenPath,
            gitLabToken = MaskedTokenUiState(draft.token, remainingDraftToken),
            gitLabInstanceUrlError = null,
            gitLabTokenPathError = null,
            gitLabTokenError = null,
            gitLabAccessReady = accessReady,
        )
        persistence.gitLab.onAccessCommitted(
            if (accessReady) GitLabApiRestConfig(committedInstanceUrl, draft.token) else null,
        )
    }

    private fun markErrorIfCurrent(
        draft: GitLabAccessDraft,
        update: (EngHubSettingsUiState) -> EngHubSettingsUiState,
    ) {
        val currentState = mutableUiState.value
        if (currentState.matchesGitLabDraft(draft)) mutableUiState.value = update(currentState)
    }
}

private data class GitLabAccessDraft(
    val instanceUrl: String,
    val normalizedInstanceUrl: String?,
    val tokenPath: String,
    val token: String,
)

private fun EngHubSettingsUiState.gitLabDraft(): GitLabAccessDraft = GitLabAccessDraft(
    instanceUrl = gitLabInstanceUrl.trim(),
    normalizedInstanceUrl = normalizeGitLabInstanceUrl(gitLabInstanceUrl),
    tokenPath = gitLabTokenPath,
    token = gitLabToken.fieldValue,
)

private fun MutableStateFlow<EngHubSettingsUiState>.validateGitLabDraft(draft: GitLabAccessDraft): Boolean {
    val urlError = GITLAB_INSTANCE_URL_ERROR.takeIf {
        draft.instanceUrl.isNotBlank() && draft.normalizedInstanceUrl == null
    }
    val pathError = GITLAB_SECRET_PATH_REQUIRED_ERROR.takeIf {
        draft.token.isNotBlank() && draft.tokenPath.isBlank()
    }
    if (urlError != null || pathError != null) {
        value = value.copy(gitLabInstanceUrlError = urlError, gitLabTokenPathError = pathError)
    }
    return urlError == null && pathError == null
}

private fun EngHubSettingsUiState.matchesGitLabDraft(draft: GitLabAccessDraft): Boolean {
    val urlMatches = gitLabInstanceUrl.trim() == draft.instanceUrl
    return urlMatches && gitLabTokenPath == draft.tokenPath && gitLabToken.fieldValue == draft.token
}
