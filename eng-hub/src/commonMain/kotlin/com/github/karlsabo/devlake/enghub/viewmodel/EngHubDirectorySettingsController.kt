package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val LOCAL_REPOSITORY_PICKER_TITLE = "Choose local repository directory"

internal class EngHubDirectorySettingsController(
    private val directoryPicker: DirectoryPicker,
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val configPersistence: EngHubConfigSettingsPersistence,
) {
    private val repositoriesBaseDir = DirectorySetting(
        persistenceKey = "repositories-base-directory",
        pickerTitle = "Choose repositories base directory",
        updateUiState = { state, path -> state.copy(repositoriesBaseDir = path) },
        updateConfig = { config, path -> config.copy(repositoriesBaseDir = path) },
    )
    private val planningMarkdownDir = DirectorySetting(
        persistenceKey = "planning-markdown-directory",
        pickerTitle = "Choose planning markdown directory",
        updateUiState = { state, path -> state.copy(planningMarkdownDir = path) },
        updateConfig = { config, path -> config.copy(planningMarkdownDir = path) },
    )

    fun updateRepositoriesBaseDir(path: String) = updateText(repositoriesBaseDir, path)

    fun chooseRepositoriesBaseDir() = choose(repositoriesBaseDir)

    fun updatePlanningMarkdownDir(path: String) = updateText(planningMarkdownDir, path)

    fun choosePlanningMarkdownDir() = choose(planningMarkdownDir)

    /** Fills the new-repository draft from the picker; adding the repository stays an explicit user action. */
    fun chooseLocalRepositoryDraft() {
        operationTracker.launch {
            val path = directoryPicker.pickDirectory(LOCAL_REPOSITORY_PICKER_TITLE) ?: return@launch
            mutableUiState.value = mutableUiState.value.copy(localRepositoryDraft = path, localRepositoryError = null)
        }
    }

    suspend fun flushPendingEdits() {
        flush(repositoriesBaseDir)
        flush(planningMarkdownDir)
    }

    private fun updateText(setting: DirectorySetting, path: String) {
        mutableUiState.value = setting.updateUiState(mutableUiState.value, path)
        setting.commitJob?.cancel()
        setting.pendingPath = path
        setting.commitJob = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            commit(setting, path)
        }
    }

    private fun choose(setting: DirectorySetting) {
        operationTracker.launch {
            val path = directoryPicker.pickDirectory(setting.pickerTitle) ?: return@launch
            setting.commitJob?.cancelAndJoin()
            setting.commitJob = null
            setting.pendingPath = path
            mutableUiState.value = setting.updateUiState(mutableUiState.value, path)
            commit(setting, path)
        }
    }

    private suspend fun flush(setting: DirectorySetting) {
        setting.commitJob?.cancelAndJoin()
        setting.commitJob = null
        setting.pendingPath?.let { path -> commit(setting, path) }
    }

    private suspend fun commit(setting: DirectorySetting, path: String) {
        val committed = configPersistence.update(setting.persistenceKey) { currentConfig ->
            setting.updateConfig(currentConfig, path)
        }
        if (committed && setting.pendingPath == path) setting.pendingPath = null
    }
}

private class DirectorySetting(
    val persistenceKey: String,
    val pickerTitle: String,
    val updateUiState: (EngHubSettingsUiState, String) -> EngHubSettingsUiState,
    val updateConfig: (EngHubConfig, String) -> EngHubConfig,
    var commitJob: Job? = null,
    var pendingPath: String? = null,
)
