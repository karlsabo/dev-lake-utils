package com.github.karlsabo.devlake.enghub.state

import com.github.karlsabo.devlake.enghub.LocalRepositoryConfig
import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.git.Worktree
import com.github.karlsabo.github.GitHubRepositoryIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class LocalRepositoryWorktreeRequest internal constructor() {
    internal val order = nextOrder.getAndUpdate { it + 1 }

    private companion object {
        val nextOrder = MutableStateFlow(0L)
    }
}

/** Identity of a continuously discovered checkout, retained across metadata and status updates. */
class LocalWorktreeCheckout internal constructor()

data class LocalRepositoryUiState(
    val name: String,
    val path: String,
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val operationRequest: LocalRepositoryWorktreeRequest? = null,
    val refreshRequest: LocalRepositoryWorktreeRequest? = null,

    /** Owns the published rows' asynchronous status hydration; a newer request discards late status results. */
    val statusRequest: LocalRepositoryWorktreeRequest? = null,
    val repositoryIdentity: GitHubRepositoryIdentity? = null,
    val worktrees: List<LocalWorktreeUiState> = emptyList(),
)

data class LocalWorktreeUiState(
    val branch: String,
    val path: String,
    val isDirty: Boolean? = null,
    val isRoot: Boolean = false,
    val parentBranch: String? = null,
    val baseCommitHash: String? = null,
    val needsRebase: Boolean = false,
    val canUpdateFromOrigin: Boolean = false,
    val integrationTargetBranch: String? = parentBranch,
    val checkout: LocalWorktreeCheckout = LocalWorktreeCheckout(),
)

data class ForceArchiveWorktreeUiState(
    val repoRootPath: String,
    val worktreePath: String,
)

fun List<LocalRepositoryConfig>.toLocalRepositoryUiStates(
    initiallyExpanded: Boolean = true,
): List<LocalRepositoryUiState> = asSequence()
    .map { it.path.trim() }
    .filter { it.isNotEmpty() }
    .map { path ->
        LocalRepositoryUiState(
            name = path.repositoryFolderName(),
            path = path,
            isExpanded = initiallyExpanded,
            isLoading = initiallyExpanded,
        )
    }
    .sortedWith(
        compareBy<LocalRepositoryUiState> { it.name.lowercase() }
            .thenBy { it.path.lowercase() },
    )
    .toList()

fun List<Worktree>.toLocalWorktreeUiStates(
    repositoryRootPath: String,
    parentBranchesByChildBranch: Map<String, String> = emptyMap(),
    needsRebaseByChildBranch: Map<String, Boolean> = emptyMap(),
): List<LocalWorktreeUiState> {
    val normalizedRepositoryRootPath = repositoryRootPath.normalizedRepositoryPath()
    val visibleBranches = map { it.branch }.filterTo(mutableSetOf()) { it.isNotBlank() }
    return map { worktree ->
        val branch = worktree.branch.ifBlank { "(detached)" }
        LocalWorktreeUiState(
            branch = branch,
            path = worktree.path,
            isDirty = worktree.isDirty,
            isRoot = worktree.path.normalizedRepositoryPath() == normalizedRepositoryRootPath,
            parentBranch = parentBranchesByChildBranch[worktree.branch]?.takeIf { it in visibleBranches },
            baseCommitHash = worktree.commitHash.takeIf { worktree.branch.isBlank() },
            needsRebase = needsRebaseByChildBranch[worktree.branch] == true,
        )
    }
}

/** Maps worktree entries for first paint; dirty status stays unknown until a local status check resolves. */
fun List<Worktree>.toLocalWorktreeUiStatesWithUnknownDirtyStatus(
    repositoryRootPath: String,
): List<LocalWorktreeUiState> = toLocalWorktreeUiStates(repositoryRootPath).map { worktree ->
    worktree.copy(isDirty = null)
}

private fun String.repositoryFolderName(): String {
    val normalized = normalizedLocalPath()
    return normalized.substringAfterLast('/').substringAfterLast('\\').ifEmpty { normalized }
}

private fun String.normalizedLocalPath(): String = trim().trimEnd('/', '\\')
