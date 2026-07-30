package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.createEngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EngHubSettingsViewModel(
    engHubConfig: EngHubConfig,
    gitHubConfig: GitHubConfig,
    gitHubSecret: GitHubSecret,
) {
    private val mutableUiState = MutableStateFlow(
        createEngHubSettingsUiState(engHubConfig, gitHubConfig, gitHubSecret),
    )

    val uiState: StateFlow<EngHubSettingsUiState> = mutableUiState.asStateFlow()
}
