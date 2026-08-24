package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.EngHubConfigWriteException
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.github.config.GitHubConfig
import com.github.karlsabo.github.config.GitHubSecret
import com.github.karlsabo.github.config.GitHubSecretWriter
import com.github.karlsabo.github.config.LoadedGitHubConfig
import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

internal const val SETTINGS_PERSISTENCE_ERROR =
    "Could not save settings. Check the configuration file path and permissions, then edit the value to retry."

class EngHubSettingsPersistence(
    internal val updateConfig: suspend ((EngHubConfig) -> EngHubConfig) -> EngHubConfig,
    internal val gitHubSecretWriter: GitHubSecretWriter,
    internal val validateGitHubSecretPath: (Path) -> Unit = {},
    internal val saveGitHubAccess: suspend (Path, GitHubSecret) -> LoadedGitHubConfig = { path, secret ->
        gitHubSecretWriter.save(path, secret)
        LoadedGitHubConfig(GitHubConfig(path.toString()), secret)
    },
    internal val onGitHubAccessCommitted: (LoadedGitHubConfig) -> Unit = {},
    internal val committedConfigUpdates: StateFlow<EngHubConfig>? = null,
) {
    internal var repositoryPathOsFamily: OsFamily = osFamily()
}

internal class EngHubConfigSettingsPersistence(
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val persistConfigUpdate: suspend ((EngHubConfig) -> EngHubConfig) -> EngHubConfig,
) {
    private val writeMutex = Mutex()
    private val pendingOperations = linkedMapOf<String, (EngHubConfig) -> EngHubConfig>()
    private var nextOperationId = 0L

    fun newOperationKey(prefix: String): String = "$prefix-${++nextOperationId}"

    suspend fun update(
        key: String,
        transform: (EngHubConfig) -> EngHubConfig,
    ): Boolean = writeMutex.withLock {
        pendingOperations[key] = transform
        drainPendingOperations()
        key !in pendingOperations
    }

    suspend fun discard(key: String) {
        writeMutex.withLock {
            pendingOperations.remove(key)
            updatePersistenceError()
        }
    }

    suspend fun retryPendingUpdates() {
        writeMutex.withLock { drainPendingOperations() }
    }

    private suspend fun drainPendingOperations() {
        while (pendingOperations.isNotEmpty()) {
            val (key, transform) = pendingOperations.entries.first()
            val persistedConfig = try {
                persistConfigUpdate(transform)
            } catch (_: EngHubConfigWriteException) {
                mutableUiState.value = mutableUiState.value.copy(persistenceError = SETTINGS_PERSISTENCE_ERROR)
                return
            }
            pendingOperations.remove(key)
            mutableUiState.value = mutableUiState.value.copy(committedConfig = persistedConfig)
        }
        updatePersistenceError()
    }

    private fun updatePersistenceError() {
        mutableUiState.value = mutableUiState.value.copy(
            persistenceError = SETTINGS_PERSISTENCE_ERROR.takeIf { pendingOperations.isNotEmpty() },
        )
    }
}
