package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.git.Worktree

data class LocalRepositoryUiState(
    val name: String,
    val path: String,
    val isExpanded: Boolean = false,
    val worktrees: List<LocalWorktreeUiState> = emptyList(),
)

data class LocalWorktreeUiState(
    val branch: String,
    val path: String,
    val isDirty: Boolean = false,
    val isRoot: Boolean = false,
)

data class ForceArchiveWorktreeUiState(
    val repoRootPath: String,
    val worktreePath: String,
)

fun List<LocalRepositoryConfig>.toLocalRepositoryUiStates(): List<LocalRepositoryUiState> {
    return asSequence()
        .map { it.path.trim() }
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

fun List<Worktree>.toLocalWorktreeUiStates(repositoryRootPath: String): List<LocalWorktreeUiState> {
    val normalizedRepositoryRootPath = repositoryRootPath.normalizedLocalPath()
    return map { worktree ->
        LocalWorktreeUiState(
            branch = worktree.branch.ifBlank { "(detached)" },
            path = worktree.path,
            isDirty = worktree.isDirty,
            isRoot = worktree.path.normalizedLocalPath() == normalizedRepositoryRootPath,
        )
    }
}

private fun String.repositoryFolderName(): String {
    val normalized = normalizedLocalPath()
    return normalized.substringAfterLast('/').substringAfterLast('\\').ifEmpty { normalized }
}

private fun String.normalizedLocalPath(): String = trim().trimEnd('/', '\\')
