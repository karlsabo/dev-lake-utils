package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

private const val DETACHED = "(detached)"

internal enum class RepositoryMenuAction {
    CreateWorktree,
}

internal enum class WorktreeMenuAction {
    Open,
    OpenPullRequest,
    CreateWorktree,
    RebaseOntoParent,
    MergeOntoParent,
    Archive,
}

internal data class WorktreeRowsState(
    val repository: LocalRepositoryUiState,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    val archivingWorktreePaths: Set<String>,
    val queuedArchiveWorktreePaths: Set<String> = emptySet(),
    val newlyQueuedArchiveWorktreePaths: Set<String> = emptySet(),
    val updatingWorktreePaths: Set<String> = emptySet(),
    val rebasingWorktreePaths: Set<String> = emptySet(),
    val mergingWorktreePaths: Set<String> = emptySet(),
    val authoredOpenPullRequests: List<PullRequestUiState> = emptyList(),
)

internal data class LocalWorktreeRowState(
    val worktree: LocalWorktreeUiState,
    val setupStatus: WorktreeSetupStatus?,
    val isArchiving: Boolean,
    val isUpdating: Boolean = false,
    val isRebasing: Boolean = false,
    val isMerging: Boolean = false,
    val nestingDepth: Int = 0,
    val connectedPullRequest: PullRequestUiState? = null,
)

internal data class VisibleWorktreeRow(
    val worktree: LocalWorktreeUiState,
    val nestingDepth: Int,
)

internal fun visibleRepositoryMenuActions(repository: LocalRepositoryUiState): List<RepositoryMenuAction> = buildList {
    if (repository.path.isNotBlank()) add(RepositoryMenuAction.CreateWorktree)
}

internal fun isRepositoryCreateWorktreeEnabled(
    repository: LocalRepositoryUiState,
    setupStatus: WorktreeSetupStatus?,
    isArchiving: Boolean,
): Boolean = repository.path.isNotBlank() && setupStatus == null && !isArchiving

internal fun visibleWorktreeMenuActions(
    worktree: LocalWorktreeUiState,
    connectedPullRequest: PullRequestUiState? = null,
): List<WorktreeMenuAction> = buildList {
    add(WorktreeMenuAction.Open)
    if (connectedPullRequest != null) add(WorktreeMenuAction.OpenPullRequest)
    add(WorktreeMenuAction.CreateWorktree)
    if (!worktree.integrationTargetBranch.isNullOrBlank() && worktree.integrationTargetBranch != worktree.branch) {
        add(WorktreeMenuAction.RebaseOntoParent)
        add(WorktreeMenuAction.MergeOntoParent)
    }
    if (!worktree.isRoot) add(WorktreeMenuAction.Archive)
}

/** Open never inspects the checkout, so it stays available while dirty status is unknown. */
internal fun isWorktreeOpenEnabled(state: LocalWorktreeRowState): Boolean = areWorktreeActionsEnabled(state)

/** Update, archive, rebase, and merge change checkout contents and require a known dirty status. */
internal fun isWorktreeStatusDependentActionEnabled(
    state: LocalWorktreeRowState,
): Boolean = areWorktreeActionsEnabled(state) && state.worktree.isDirty != null

internal fun isWorktreeCreateEnabled(
    state: LocalWorktreeRowState,
): Boolean = areWorktreeActionsEnabled(state) && state.worktree.hasCreatableBase()

private fun areWorktreeActionsEnabled(state: LocalWorktreeRowState): Boolean = state.setupStatus == null &&
    !state.isArchiving &&
    !state.isUpdating &&
    !(state.isRebasing || state.isMerging)

private fun LocalWorktreeUiState.hasCreatableBase(): Boolean = branch != DETACHED || !baseCommitHash.isNullOrBlank()
