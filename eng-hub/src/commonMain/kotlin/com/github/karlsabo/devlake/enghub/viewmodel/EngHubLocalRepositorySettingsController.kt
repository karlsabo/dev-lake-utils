package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState
import com.github.karlsabo.system.OsFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val LOCAL_REPOSITORY_BLANK_ERROR = "Enter a repository path"
internal const val LOCAL_REPOSITORY_DUPLICATE_ERROR = "Repository path already exists"
internal const val LOCAL_REPOSITORY_UNDO_DURATION_MS = 5_000L

internal data class EngHubLocalRepositorySettingsDependencies(
    val coroutineScope: CoroutineScope,
    val operationTracker: EngHubSettingsOperationTracker,
    val configPersistence: EngHubConfigSettingsPersistence,
    val pathEditor: EngHubLocalRepositoryPathEditor,
    val osFamily: OsFamily,
)

internal data class RepositoryStructureCallbacks(
    val onRemoved: (Int) -> Unit,
    val onInserted: (Int) -> Unit,
)

internal class EngHubLocalRepositorySettingsController(
    private val dependencies: EngHubLocalRepositorySettingsDependencies,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val structureCallbacks: RepositoryStructureCallbacks,
) {
    private var pendingRemoval: PendingRepositoryRemoval? = null
    private var removalPersistenceJob: Job? = null
    private var removalExpirationJob: Job? = null
    private var removalId = 0L

    fun updateDraft(path: String) {
        mutableUiState.value = mutableUiState.value.copy(localRepositoryDraft = path, localRepositoryError = null)
    }

    fun add() {
        val state = mutableUiState.value
        val path = state.localRepositoryDraft
        val error = path.localRepositoryValidationError(state.localRepositories.map { it.path }, dependencies.osFamily)
        if (error != null) {
            mutableUiState.value = state.copy(localRepositoryError = error)
            return
        }

        dependencies.pathEditor.addRepository(path)
        mutableUiState.value = state.copy(
            localRepositories = state.localRepositories + SettingsLocalRepositoryUiState(
                path = path,
                setupCommands = emptyList(),
            ),
            localRepositoryDraft = "",
            localRepositoryError = null,
        )
        val operationKey = dependencies.configPersistence.newOperationKey("repository-add")
        dependencies.operationTracker.launch {
            dependencies.configPersistence.update(operationKey) { currentConfig ->
                currentConfig.copy(localRepositories = currentConfig.localRepositories + LocalRepositoryConfig(path))
            }
        }
    }

    fun updatePath(repositoryIndex: Int, path: String) {
        dependencies.pathEditor.updatePath(repositoryIndex, path)
    }

    fun choosePath(repositoryIndex: Int) {
        dependencies.pathEditor.choosePath(repositoryIndex)
    }

    fun remove(repositoryIndex: Int) {
        val state = mutableUiState.value
        if (repositoryIndex !in state.localRepositories.indices) return
        val removedIdentity = dependencies.pathEditor.removeRepository(repositoryIndex) ?: return
        structureCallbacks.onRemoved(repositoryIndex)

        val removal = PendingRepositoryRemoval(
            id = ++removalId,
            repositoryId = removedIdentity.id,
            persistedPath = removedIdentity.persistedPath,
            requestedIndex = repositoryIndex,
        )
        pendingRemoval = removal
        removalExpirationJob?.cancel()
        mutableUiState.value = state.copy(
            localRepositories = state.localRepositories.filterIndexed { index, _ -> index != repositoryIndex },
            removedLocalRepositoryPath = removedIdentity.persistedPath,
        )
        persistRemoval(removedIdentity, removal)
        scheduleRemovalExpiration(removal)
    }

    fun undoRemoval() {
        val removal = pendingRemoval ?: return
        pendingRemoval = null
        removalExpirationJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(removedLocalRepositoryPath = null)
        persistRestoration(removal)
    }

    suspend fun flushPendingEdits() {
        dependencies.pathEditor.flushPendingEdits()
    }

    private fun persistRemoval(
        identity: RemovedRepositoryIdentity,
        removal: PendingRepositoryRemoval,
    ) {
        val operationKey = dependencies.configPersistence.newOperationKey("repository-remove")
        removalPersistenceJob = dependencies.operationTracker.launch {
            dependencies.configPersistence.update(operationKey) { currentConfig ->
                val removed = currentConfig.removeLocalRepository(
                    repositoryIndex = identity.repositoryIndex,
                    expectedPath = identity.persistedPath,
                    pendingReplacementPath = identity.pendingReplacementPath,
                )
                removal.repository = removed.repository
                removal.persistedIndex = removed.index
                removed.config
            }
        }
    }

    private fun persistRestoration(removal: PendingRepositoryRemoval) {
        val pendingPersistence = removalPersistenceJob
        val operationKey = dependencies.configPersistence.newOperationKey("repository-restore")
        removalPersistenceJob = dependencies.operationTracker.launch {
            pendingPersistence?.join()
            val repository = removal.repository ?: return@launch
            dependencies.pathEditor.withReservedRestoration(repository.path) {
                var restorationApplied = false
                val persisted = dependencies.configPersistence.update(operationKey) { currentConfig ->
                    val restored = currentConfig.withLocalRepositoryRestored(repository, removal.persistedIndex)
                    restorationApplied = restored != currentConfig
                    restored
                }
                if (!persisted || !restorationApplied) return@withReservedRestoration

                val insertionIndex = removal.persistedIndex.coerceIn(
                    minimumValue = 0,
                    maximumValue = mutableUiState.value.localRepositories.size,
                )
                val restoredRepositories = mutableUiState.value.localRepositories.withRepositoryRestored(
                    repository,
                    insertionIndex,
                )
                if (restoredRepositories.size == mutableUiState.value.localRepositories.size) {
                    return@withReservedRestoration
                }
                val identityIndex = dependencies.pathEditor.insertRepository(
                    insertionIndex,
                    removal.repositoryId,
                    repository.path,
                ) ?: return@withReservedRestoration
                mutableUiState.value = mutableUiState.value.copy(localRepositories = restoredRepositories)
                structureCallbacks.onInserted(identityIndex)
            }
        }
    }

    private fun scheduleRemovalExpiration(removal: PendingRepositoryRemoval) {
        removalExpirationJob = dependencies.coroutineScope.launch {
            delay(LOCAL_REPOSITORY_UNDO_DURATION_MS.milliseconds)
            if (pendingRemoval?.id == removal.id) {
                pendingRemoval = null
                mutableUiState.value = mutableUiState.value.copy(removedLocalRepositoryPath = null)
            }
        }
    }
}

internal fun reconcileSettingsRepositories(
    config: EngHubConfig,
    mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    pathEditor: EngHubLocalRepositoryPathEditor,
    osFamily: OsFamily,
) {
    val state = mutableUiState.value
    val previousPaths = state.committedConfig.localRepositories.mapTo(mutableSetOf()) { repository ->
        repository.path.normalizedRepositoryPath(osFamily)
    }
    val committedPaths = config.localRepositories.mapTo(mutableSetOf()) { repository ->
        repository.path.normalizedRepositoryPath(osFamily)
    }
    val addedRepositories = config.localRepositories.filter { repository ->
        repository.path.normalizedRepositoryPath(osFamily) !in previousPaths &&
            !pathEditor.representsCommittedRepository(repository.path, committedPaths)
    }
    addedRepositories.forEach { repository -> pathEditor.addRepository(repository.path) }
    mutableUiState.value = state.copy(
        committedConfig = config,
        localRepositories = state.localRepositories + addedRepositories.map { repository ->
            SettingsLocalRepositoryUiState(repository.path, repository.setupCommands)
        },
    )
    pathEditor.revalidatePendingPaths()
}
