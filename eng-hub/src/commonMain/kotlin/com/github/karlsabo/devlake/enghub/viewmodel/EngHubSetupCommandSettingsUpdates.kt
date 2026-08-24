package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.EngHubConfig

internal fun EngHubConfig.withSetupCommandReplaced(
    repositoryIndex: Int,
    expectedRepositoryPath: String,
    commandIndex: Int,
    replacementCommand: String,
): EngHubConfig {
    val actualRepositoryIndex = matchingRepositoryIndex(repositoryIndex, expectedRepositoryPath)
    val repository = localRepositories.getOrNull(actualRepositoryIndex)
    return if (repository == null || commandIndex !in repository.setupCommands.indices) {
        this
    } else {
        val updatedRepositories = localRepositories.toMutableList()
        updatedRepositories[actualRepositoryIndex] = repository.copy(
            setupCommands = repository.setupCommands.withReplaced(commandIndex, replacementCommand),
        )
        copy(localRepositories = updatedRepositories)
    }
}

private fun EngHubConfig.matchingRepositoryIndex(index: Int, expectedPath: String): Int {
    val indexedMatch = index.takeIf { localRepositories.getOrNull(it)?.path == expectedPath }
    return indexedMatch ?: localRepositories.indexOfFirst { repository -> repository.path == expectedPath }
}

internal fun EngHubConfig.withSetupCommandRemoved(
    repositoryIndex: Int,
    expectedRepositoryPath: String,
    commandIndex: Int,
): EngHubConfig {
    val repository = localRepositories.getOrNull(repositoryIndex)
    val canRemove = repository?.path == expectedRepositoryPath && commandIndex in repository.setupCommands.indices
    if (repository == null || !canRemove) return this

    val updatedRepositories = localRepositories.toMutableList()
    updatedRepositories[repositoryIndex] = repository.copy(
        setupCommands = repository.setupCommands.withRemovedAt(commandIndex),
    )
    return copy(localRepositories = updatedRepositories)
}

internal fun EngHubConfig.withSetupCommandInserted(
    repositoryIndex: Int,
    expectedRepositoryPath: String,
    insertionIndex: Int,
    command: String,
): EngHubConfig {
    val repository = localRepositories.getOrNull(repositoryIndex)
    val canInsert =
        repository?.path == expectedRepositoryPath && insertionIndex in 0..repository.setupCommands.size
    return if (repository == null || !canInsert) {
        this
    } else {
        val updatedRepositories = localRepositories.toMutableList()
        updatedRepositories[repositoryIndex] = repository.copy(
            setupCommands = repository.setupCommands.withInserted(insertionIndex, command),
        )
        copy(localRepositories = updatedRepositories)
    }
}

internal fun <T> List<T>.withRemovedAt(index: Int): List<T> = filterIndexed { itemIndex, _ -> itemIndex != index }

internal fun <T> List<T>.withReplaced(index: Int, value: T): List<T> = mapIndexed { itemIndex, item ->
    if (itemIndex == index) value else item
}
