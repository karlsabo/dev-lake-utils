package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig
import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.SettingsLocalRepositoryUiState
import com.github.karlsabo.system.OsFamily
import com.github.karlsabo.system.osFamily
import kotlinx.coroutines.flow.MutableStateFlow

internal fun MutableStateFlow<EngHubSettingsUiState>.updateRepositoryUiState(
    repositoryIndex: Int,
    transform: (SettingsLocalRepositoryUiState) -> SettingsLocalRepositoryUiState,
) {
    val repositories = value.localRepositories
    if (repositoryIndex !in repositories.indices) return
    value = value.copy(
        localRepositories = repositories.mapIndexed { index, repository ->
            if (index == repositoryIndex) transform(repository) else repository
        },
    )
}

internal fun String.localRepositoryValidationError(
    existingPaths: List<String>,
    family: OsFamily = osFamily(),
): String? = when {
    isBlank() -> LOCAL_REPOSITORY_BLANK_ERROR

    existingPaths.any { it.normalizedRepositoryPath(family) == normalizedRepositoryPath(family) } -> {
        LOCAL_REPOSITORY_DUPLICATE_ERROR
    }

    else -> null
}

internal fun EngHubConfig.withLocalRepositoryPathReplaced(
    repositoryIndex: Int,
    replacementPath: String,
    family: OsFamily = osFamily(),
): EngHubConfig {
    val normalizedReplacement = replacementPath.normalizedRepositoryPath(family)
    val replacementIsDuplicate = localRepositories.anyIndexed { index, repository ->
        index != repositoryIndex && repository.path.normalizedRepositoryPath(family) == normalizedReplacement
    }
    return if (repositoryIndex !in localRepositories.indices || replacementIsDuplicate) {
        this
    } else {
        val updatedRepositories = localRepositories.toMutableList()
        updatedRepositories[repositoryIndex] = localRepositories[repositoryIndex].copy(path = replacementPath)
        copy(localRepositories = updatedRepositories)
    }
}

internal fun EngHubConfig.removeLocalRepository(
    repositoryIndex: Int,
    expectedPath: String,
    pendingReplacementPath: String? = null,
): RemovedLocalRepositoryConfig {
    val expectedPaths = setOfNotNull(expectedPath, pendingReplacementPath)
    val matchingIndex = repositoryIndex.takeIf { index ->
        localRepositories.getOrNull(index)?.path in expectedPaths
    } ?: localRepositories.indexOfFirst { repository -> repository.path in expectedPaths }
    return if (matchingIndex == -1) {
        RemovedLocalRepositoryConfig(this, null, repositoryIndex)
    } else {
        RemovedLocalRepositoryConfig(
            config = copy(localRepositories = localRepositories.filterIndexed { index, _ -> index != matchingIndex }),
            repository = localRepositories[matchingIndex],
            index = matchingIndex,
        )
    }
}

internal data class RemovedLocalRepositoryConfig(
    val config: EngHubConfig,
    val repository: LocalRepositoryConfig?,
    val index: Int,
)

internal fun EngHubConfig.withLocalRepositoryRestored(
    repository: LocalRepositoryConfig,
    index: Int,
): EngHubConfig {
    if (localRepositories.any { it.path.normalizedRepositoryPath() == repository.path.normalizedRepositoryPath() }) {
        return this
    }
    val restoredRepositories = localRepositories.withInserted(
        index.coerceIn(0, localRepositories.size),
        repository,
    )
    return copy(localRepositories = restoredRepositories)
}

internal fun List<SettingsLocalRepositoryUiState>.withRepositoryRestored(
    repository: LocalRepositoryConfig,
    index: Int,
): List<SettingsLocalRepositoryUiState> {
    if (any { it.path.normalizedRepositoryPath() == repository.path.normalizedRepositoryPath() }) return this
    val restoredRepository = SettingsLocalRepositoryUiState(
        path = repository.path,
        setupCommands = repository.setupCommands,
    )
    return withInserted(index.coerceIn(0, size), restoredRepository)
}

internal data class PendingRepositoryRemoval(
    val id: Long,
    val repositoryId: Long,
    val persistedPath: String,
    val requestedIndex: Int,
    var repository: LocalRepositoryConfig? = null,
    var persistedIndex: Int = requestedIndex,
)

internal fun <T> List<T>.withInserted(index: Int, value: T): List<T> = subList(0, index) + value + subList(index, size)

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value -> if (predicate(index, value)) return true }
    return false
}
