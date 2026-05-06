package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.git.Worktree

data class LocalRepositoryUiState(
    val name: String,
    val path: String,
    val worktrees: List<LocalWorktreeUiState> = emptyList(),
)

data class LocalWorktreeUiState(
    val branch: String,
    val path: String,
)

fun List<String>.toLocalRepositoryUiStates(): List<LocalRepositoryUiState> {
    return asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { path ->
            LocalRepositoryUiState(
                name = path.repositoryFolderName(),
                path = path,
            )
        }
        .sortedWith(
            compareBy<LocalRepositoryUiState> { it.name.lowercase() }
                .thenBy { it.path.lowercase() }
        )
        .toList()
}

fun List<Worktree>.toLocalWorktreeUiStates(): List<LocalWorktreeUiState> {
    return map { worktree ->
        LocalWorktreeUiState(
            branch = worktree.branch.ifBlank { "(detached)" },
            path = worktree.path,
        )
    }
}

private fun String.repositoryFolderName(): String {
    val normalized = trimEnd('/', '\\')
    return normalized.substringAfterLast('/').substringAfterLast('\\').ifEmpty { normalized }
}
