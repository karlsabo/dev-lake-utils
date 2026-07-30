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
}
