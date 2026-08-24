package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.system.OsFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal data class RemovedRepositoryIdentity(
    val id: Long,
    val repositoryIndex: Int,
    val persistedPath: String,
    val pendingReplacementPath: String?,
)

internal class EngHubLocalRepositoryPathEditor(
    private val directoryPicker: DirectoryPicker,
    private val coroutineScope: CoroutineScope,
    private val operationTracker: EngHubSettingsOperationTracker,
    private val mutableUiState: MutableStateFlow<EngHubSettingsUiState>,
    private val configPersistence: EngHubConfigSettingsPersistence,
    private val osFamily: OsFamily,
) {
    private val repositoryIds = RepositoryIds(
        mutableUiState.value.committedConfig.localRepositories.map { repository -> repository.path },
        osFamily,
    )
    private val pathCommitJobs = mutableMapOf<Long, Job>()
    private val pendingPathEdits = mutableMapOf<Long, PendingRepositoryPathEdit>()
    private val reservedRestorationPaths = mutableSetOf<String>()

    val addRepository: (String) -> Unit = repositoryIds::add

    fun updatePath(repositoryIndex: Int, path: String) {
        if (!repositoryIds.contains(repositoryIndex)) return
        val id = repositoryIds[repositoryIndex]
        val existingEdit = pendingPathEdits.remove(id)
        pathCommitJobs.remove(id)?.cancel()
        val error = path.localRepositoryValidationError(mutableUiState.otherRepositoryPaths(repositoryIndex), osFamily)
        mutableUiState.updateRepositoryUiState(repositoryIndex) { it.copy(path = path, pathError = error) }
        if (error == null) {
            schedulePathCommit(
                PendingRepositoryPathEdit(
                    repositoryId = id,
                    repositoryIndex = repositoryIndex,
                    persistenceKey = existingEdit?.persistenceKey
                        ?: configPersistence.newOperationKey("repository-path"),
                    expectedPath = existingEdit?.expectedPath ?: repositoryIds.persistedPath(id),
                    replacementPath = path,
                ),
            )
        } else if (existingEdit != null) {
            operationTracker.launch { configPersistence.discard(existingEdit.persistenceKey) }
        }
    }

    fun choosePath(repositoryIndex: Int) {
        val id = repositoryIds.idOrNull(repositoryIndex) ?: return
        operationTracker.launch {
            val selectedPath = directoryPicker.pickDirectory("Choose local repository directory") ?: return@launch
            val currentIndex = repositoryIds.indexOf(id)
            if (currentIndex == -1) return@launch
            val error = selectedPath.localRepositoryValidationError(
                mutableUiState.otherRepositoryPaths(currentIndex),
                osFamily,
            )
            if (error != null) {
                mutableUiState.updateRepositoryUiState(currentIndex) { it.copy(pathError = error) }
                return@launch
            }

            val existingEdit = pendingPathEdits.remove(id)
            pathCommitJobs.remove(id)?.cancelAndJoin()
            val edit = PendingRepositoryPathEdit(
                repositoryId = id,
                repositoryIndex = currentIndex,
                persistenceKey = existingEdit?.persistenceKey
                    ?: configPersistence.newOperationKey("repository-path"),
                expectedPath = existingEdit?.expectedPath ?: repositoryIds.persistedPath(id),
                replacementPath = selectedPath,
            )
            mutableUiState.updateRepositoryUiState(currentIndex) { it.copy(path = selectedPath, pathError = null) }
            pendingPathEdits[id] = edit
            commitPath(edit)
        }
    }

    fun removeRepository(repositoryIndex: Int): RemovedRepositoryIdentity? {
        val id = repositoryIds.idOrNull(repositoryIndex) ?: return null
        val persistedPath = repositoryIds.persistedPath(id)
        val pendingReplacementPath = pendingPathEdits[id]?.replacementPath
        cancelRepositoryEdit(id)
        repositoryIds.remove(repositoryIndex)
        rebasePathEdits { index -> if (index > repositoryIndex) index - 1 else index }
        return RemovedRepositoryIdentity(id, repositoryIndex, persistedPath, pendingReplacementPath)
    }

    suspend fun withReservedRestoration(path: String, restore: suspend () -> Unit) {
        val normalizedPath = path.normalizedRepositoryPath(osFamily)
        if (repositoryIds.hasPersistedPath(normalizedPath) || !reservedRestorationPaths.add(normalizedPath)) return
        try {
            restore()
        } finally {
            reservedRestorationPaths.remove(normalizedPath)
        }
    }

    val insertRepository: (Int, Long, String) -> Int? = insertRepository@{ requestedIndex, id, persistedPath ->
        val normalizedPath = persistedPath.normalizedRepositoryPath(osFamily)
        if (repositoryIds.hasPersistedPath(normalizedPath)) return@insertRepository null
        val index = repositoryIds.insert(requestedIndex, id, persistedPath)
        rebasePathEdits { currentIndex -> if (currentIndex >= index) currentIndex + 1 else currentIndex }
        index
    }

    val repositoryOperationTarget: (Int) -> Long? = repositoryIds::idOrNull

    val prepareForRepositoryOperation: suspend (Long) -> String = ::prepareRepositoryPath

    val prepareForIndexedRepositoryOperation: suspend (Int) -> String? = { repositoryIndex ->
        repositoryIds.idOrNull(repositoryIndex)?.let { id -> prepareRepositoryPath(id) }
    }

    suspend fun flushPendingEdits() {
        val jobs = pathCommitJobs.values.toList()
        pathCommitJobs.clear()
        jobs.forEach { it.cancelAndJoin() }
        pendingPathEdits.values.toList().forEach { edit ->
            if (commitPath(edit)) pendingPathEdits.remove(edit.repositoryId)
        }
    }

    val representsCommittedRepository: (String, Set<String>) -> Boolean = { path, committedPaths ->
        val normalizedPath = path.normalizedRepositoryPath(osFamily)
        repositoryIds.hasPersistedPath(normalizedPath) || normalizedPath in reservedRestorationPaths ||
            pendingPathEdits.values.any { edit ->
                edit.replacementPath.normalizedRepositoryPath(osFamily) == normalizedPath &&
                    edit.expectedPath.normalizedRepositoryPath(osFamily) !in committedPaths
            }
    }

    val revalidatePendingPaths: () -> Unit = {
        pendingPathEdits.values.forEach { edit ->
            val repositoryIndex = repositoryIds.indexOf(edit.repositoryId)
            if (repositoryIndex != -1) {
                val error = edit.replacementPath.localRepositoryValidationError(
                    mutableUiState.value.committedConfig.localRepositories
                        .filterNot { repository -> repository.path == edit.expectedPath }
                        .map { repository -> repository.path },
                    osFamily,
                )
                if (error != null) pathCommitJobs.remove(edit.repositoryId)?.cancel()
                mutableUiState.updateRepositoryUiState(repositoryIndex) { repository ->
                    repository.copy(pathError = error)
                }
            }
        }
    }

    private suspend fun prepareRepositoryPath(id: Long): String {
        val edit = pendingPathEdits[id] ?: return repositoryIds.persistedPath(id)
        pathCommitJobs.remove(id)?.cancelAndJoin()
        val committed = commitPath(edit)
        if (committed && pendingPathEdits[id] == edit) pendingPathEdits.remove(id)
        return if (committed) edit.replacementPath else repositoryIds.persistedPath(id)
    }

    private fun schedulePathCommit(edit: PendingRepositoryPathEdit) {
        pendingPathEdits[edit.repositoryId] = edit
        pathCommitJobs[edit.repositoryId] = coroutineScope.launch {
            delay(TEXT_COMMIT_DEBOUNCE_MS.milliseconds)
            if (commitPath(edit) && pendingPathEdits[edit.repositoryId] == edit) {
                pendingPathEdits.remove(edit.repositoryId)
                pathCommitJobs.remove(edit.repositoryId)
            }
        }
    }

    private suspend fun commitPath(edit: PendingRepositoryPathEdit): Boolean {
        val validationError = edit.validationError(mutableUiState.value, osFamily)
        if (validationError != null) {
            mutableUiState.markPathError(repositoryIds, edit, validationError)
        }
        var pathReplaced = false
        val committed = validationError == null && configPersistence.update(edit.persistenceKey) { currentConfig ->
            val updatedConfig = currentConfig.withLocalRepositoryPathReplaced(
                repositoryIndex = edit.repositoryIndex,
                replacementPath = edit.replacementPath,
                family = osFamily,
            )
            pathReplaced = updatedConfig != currentConfig
            updatedConfig
        }
        val succeeded = committed && pathReplaced
        if (succeeded) {
            repositoryIds.updatePersistedPath(edit.repositoryId, edit.replacementPath)
        } else if (committed) {
            mutableUiState.markPathError(repositoryIds, edit, LOCAL_REPOSITORY_DUPLICATE_ERROR)
        }
        return succeeded
    }

    private fun cancelRepositoryEdit(id: Long) {
        pathCommitJobs.remove(id)?.cancel()
        pendingPathEdits.remove(id)?.let { edit ->
            operationTracker.launch { configPersistence.discard(edit.persistenceKey) }
        }
    }

    private fun rebasePathEdits(rebase: (Int) -> Int) {
        pendingPathEdits.values.toList().forEach { edit ->
            val rebasedEdit = edit.copy(repositoryIndex = rebase(edit.repositoryIndex))
            pathCommitJobs.remove(edit.repositoryId)?.cancel()
            schedulePathCommit(rebasedEdit)
        }
    }
}

private class RepositoryIds(
    initialPaths: List<String>,
    private val osFamily: OsFamily,
) {
    private var nextId = 0L
    private val identities = initialPaths.associateTo(mutableMapOf()) { path ->
        val identity = RepositoryIdentity(++nextId, path)
        identity.id to identity
    }
    private val orderedIds = identities.keys.toMutableList()

    operator fun get(index: Int): Long = orderedIds[index]
    fun contains(index: Int): Boolean = index in orderedIds.indices
    fun idOrNull(index: Int): Long? = orderedIds.getOrNull(index)
    fun indexOf(id: Long): Int = orderedIds.indexOf(id)
    fun persistedPath(id: Long): String = identities.getValue(id).persistedPath
    fun hasPersistedPath(normalizedPath: String): Boolean = orderedIds.any { id ->
        identities.getValue(id).persistedPath.normalizedRepositoryPath(osFamily) == normalizedPath
    }

    fun add(path: String) {
        val identity = RepositoryIdentity(++nextId, path)
        identities[identity.id] = identity
        orderedIds += identity.id
    }

    fun remove(index: Int): Long = orderedIds.removeAt(index)

    fun insert(
        requestedIndex: Int,
        id: Long,
        persistedPath: String,
    ): Int {
        val index = requestedIndex.coerceIn(0, orderedIds.size)
        identities[id] = RepositoryIdentity(id, persistedPath)
        orderedIds.add(index, id)
        return index
    }

    fun updatePersistedPath(id: Long, path: String) {
        identities[id] = identities.getValue(id).copy(persistedPath = path)
    }
}

private data class RepositoryIdentity(
    val id: Long,
    val persistedPath: String,
)

private data class PendingRepositoryPathEdit(
    val repositoryId: Long,
    val repositoryIndex: Int,
    val persistenceKey: String,
    val expectedPath: String,
    val replacementPath: String,
)

private fun PendingRepositoryPathEdit.validationError(
    state: EngHubSettingsUiState,
    osFamily: OsFamily,
): String? {
    val otherPaths = state.committedConfig.localRepositories
        .filterNot { repository -> repository.path == expectedPath }
        .map { repository -> repository.path }
    return replacementPath.localRepositoryValidationError(otherPaths, osFamily)
}

private fun MutableStateFlow<EngHubSettingsUiState>.markPathError(
    repositoryIds: RepositoryIds,
    edit: PendingRepositoryPathEdit,
    error: String,
) {
    val index = repositoryIds.indexOf(edit.repositoryId)
    if (index != -1) updateRepositoryUiState(index) { repository -> repository.copy(pathError = error) }
}

private fun MutableStateFlow<EngHubSettingsUiState>.otherRepositoryPaths(
    repositoryIndex: Int,
): List<String> = value.localRepositories.mapIndexedNotNull { index, repository ->
    repository.path.takeIf { index != repositoryIndex }
}
