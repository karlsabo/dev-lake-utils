package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.FilePicker
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.GitHubTokenUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubConfigWriteException
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriteException
import com.github.karlsabo.github.config.LoadedGitHubConfig
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

internal const val GITHUB_SECRET_SAVE_ERROR =
    "Could not save the GitHub token securely. Check the secret path and file permissions."
internal const val GITHUB_CONFIG_SAVE_ERROR =
    "The token was saved, but GitHub configuration could not be updated. Check the configuration file permissions."
internal const val GITHUB_SECRET_PATH_REQUIRED_ERROR = "Enter a GitHub secret file path"
private const val GITHUB_SECRET_PICKER_TITLE = "Choose GitHub secret file"

internal class EngHubGitHubTokenController(
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val filePicker: FilePicker,
    private val persistence: EngHubSettingsPersistence,
) {
    private val writeMutex = Mutex()
    private var committedPath = mutableUiState.value.gitHubTokenPath
    private var commitJob: Job? = null
    private var hasPendingEdit = false

    fun updateSecretPath(path: String) {
        mutableUiState.value = mutableUiState.value.copy(gitHubTokenPath = path, gitHubTokenPathError = null)
        scheduleCommit()
    }

    fun chooseSecretPath() {
        operationTracker.launch {
            val path = filePicker.pickFilePath(GITHUB_SECRET_PICKER_TITLE) ?: return@launch
            commitJob?.cancelAndJoin()
            commitJob = null
            hasPendingEdit = true
            mutableUiState.value = mutableUiState.value.copy(gitHubTokenPath = path, gitHubTokenPathError = null)
            commitCurrentDraft()
        }
    }

    fun updateToken(token: String) {
        commitJob?.cancel()
        val tokenState = mutableUiState.value.gitHubToken
        mutableUiState.value = mutableUiState.value.copy(
            gitHubToken = if (token == tokenState.value) {
                tokenState.withCommittedToken(token)
            } else {
                tokenState.withDraft(token)
            },
            gitHubTokenError = null,
        )
        scheduleCommit()
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
        val state = mutableUiState.value
        val draft = GitHubAccessDraft(state.gitHubTokenPath, state.gitHubToken.fieldValue)
        if (!mutableUiState.validate(draft)) return

        val unchanged = draft.path == committedPath && draft.token == state.gitHubToken.value
        if (unchanged) {
            if (mutableUiState.value.matchesGitHubDraft(draft)) hasPendingEdit = false
        } else {
            persistDraft(draft)
        }
    }

    private suspend fun persistDraft(draft: GitHubAccessDraft) {
        writeMutex.withLock {
            try {
                val loadedConfig = saveDraft(draft)
                persistence.onGitHubAccessCommitted(loadedConfig)
                markCommitted(draft)
            } catch (_: GitHubSecretWriteException) {
                markSaveFailed(draft, tokenError = GITHUB_SECRET_SAVE_ERROR)
            } catch (_: GitHubConfigWriteException) {
                markSaveFailed(draft, pathError = GITHUB_CONFIG_SAVE_ERROR)
            }
        }
    }

    private suspend fun saveDraft(draft: GitHubAccessDraft): LoadedGitHubConfig {
        val secretPath = Path(draft.path)
        persistence.validateGitHubSecretPath(secretPath)
        val secret = GitHubSecret(draft.token)
        return if (draft.path == committedPath) {
            persistence.gitHubSecretWriter.save(secretPath, secret)
            LoadedGitHubConfig(GitHubConfig(draft.path), secret)
        } else {
            persistence.saveGitHubAccess(secretPath, secret)
        }
    }

    private fun markCommitted(draft: GitHubAccessDraft) {
        committedPath = draft.path
        val currentState = mutableUiState.value
        val currentDraft = currentState.gitHubToken.fieldValue.takeUnless { currentState.matchesGitHubDraft(draft) }
        mutableUiState.value = currentState.copy(
            gitHubToken = GitHubTokenUiState(draft.token, currentDraft),
            gitHubTokenPathError = null,
            gitHubTokenError = null,
            gitHubAccessReady = draft.path.isNotBlank() && draft.token.isNotBlank(),
        )
        hasPendingEdit = currentDraft != null || currentState.gitHubTokenPath != draft.path
    }

    private fun markSaveFailed(
        draft: GitHubAccessDraft,
        pathError: String? = null,
        tokenError: String? = null,
    ) {
        if (mutableUiState.value.matchesGitHubDraft(draft)) {
            mutableUiState.value = mutableUiState.value.copy(
                gitHubTokenPathError = pathError,
                gitHubTokenError = tokenError,
            )
            hasPendingEdit = true
        }
    }
}

private data class GitHubAccessDraft(
    val path: String,
    val token: String,
)

private fun MutableStateFlow<EngHubSettingsUiState>.validate(draft: GitHubAccessDraft): Boolean {
    val pathError = if (draft.path.isBlank()) GITHUB_SECRET_PATH_REQUIRED_ERROR else null
    if (pathError != null) {
        value = value.copy(gitHubTokenPathError = pathError)
    }
    return pathError == null
}

private fun EngHubSettingsUiState.matchesGitHubDraft(draft: GitHubAccessDraft): Boolean {
    val pathMatches = gitHubTokenPath == draft.path
    return pathMatches && gitHubToken.fieldValue == draft.token
}
