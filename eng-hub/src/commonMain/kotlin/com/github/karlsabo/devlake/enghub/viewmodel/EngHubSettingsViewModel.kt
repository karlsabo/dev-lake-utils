package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val TEXT_COMMIT_DEBOUNCE_MS = 750L
private const val MILLISECONDS_PER_SECOND = 1_000L
internal const val POLL_INTERVAL_ERROR = "Enter a positive whole number of seconds"

class EngHubSettingsViewModel(
    engHubConfig: EngHubConfig,
    gitHubConfig: GitHubConfig,
    gitHubSecret: GitHubSecret,
    private val updateConfig: suspend ((EngHubConfig) -> EngHubConfig) -> EngHubConfig,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutableUiState = MutableStateFlow(
        createEngHubSettingsUiState(engHubConfig, gitHubConfig, gitHubSecret),
    )
    private var authorCommitJob: Job? = null
    private var pollIntervalCommitJob: Job? = null

    val uiState: StateFlow<EngHubSettingsUiState> = mutableUiState.asStateFlow()

    fun updateGitHubAuthor(author: String) {
        mutableUiState.value = mutableUiState.value.copy(gitHubAuthor = author)
        authorCommitJob?.cancel()
        authorCommitJob = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            updateConfig { currentConfig ->
                currentConfig.copy(gitHubAuthor = author)
            }
        }
    }

    fun updatePollIntervalSeconds(seconds: String) {
        pollIntervalCommitJob?.cancel()
        val intervalMs = seconds.toPollIntervalMillisecondsOrNull()
        mutableUiState.value = mutableUiState.value.copy(
            pollIntervalSeconds = seconds,
            pollIntervalError = if (intervalMs == null) POLL_INTERVAL_ERROR else null,
        )
        if (intervalMs == null) return
        pollIntervalCommitJob = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            updateConfig { currentConfig ->
                currentConfig.copy(pollIntervalMs = intervalMs)
            }
        }
    }
}

private fun String.toPollIntervalMillisecondsOrNull(): Long? {
    val seconds = toLongOrNull()
    return if (seconds != null && seconds > 0 && seconds <= Long.MAX_VALUE / MILLISECONDS_PER_SECOND) {
        seconds * MILLISECONDS_PER_SECOND
    } else {
        null
    }
}
