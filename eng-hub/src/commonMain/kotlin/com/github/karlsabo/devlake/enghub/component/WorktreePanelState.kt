package com.github.karlsabo.devlake.enghub.component

import com.github.karlsabo.devlake.enghub.state.ForceArchiveWorktreeUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal typealias CreateWorktreeCallback = (PendingCreateWorktree) -> Unit

internal data class WorktreePanelState(
    val localRepositories: List<LocalRepositoryUiState>,
    val forceArchiveRequest: ForceArchiveWorktreeUiState?,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
    val archivingWorktreePaths: Set<String>,
    val rebasingWorktreePaths: Set<String> = emptySet(),
    val repositoryCreateWorktreeRequest: PendingCreateWorktree? = null,
    val existingBranchDiscovery: ExistingBranchDiscoveryUiState = ExistingBranchDiscoveryUiState(),
    val useUnrelatedExistingBranchConfirmationRequest: PendingUseUnrelatedExistingBranch? = null,
    val rebaseConflictResolutionRequest: PendingRebaseConflictResolution? = null,
)

internal data class WorktreePanelActions(
    val onAddRepository: () -> Unit,
    val onToggleRepository: (String) -> Unit,
    val onCreateWorktreeFromRepository: (String) -> Unit,
    val onRepositoryCreateWorktreeRequestHandled: () -> Unit,
    val onDiscoverExistingBranches: (String) -> Unit,
    val onCheckoutExistingBranch: (repoRootPath: String, branch: String) -> Unit,
    val onConfirmUseUnrelatedExistingBranch: (PendingUseUnrelatedExistingBranch) -> Unit,
    val onDismissUseUnrelatedExistingBranchConfirmation: () -> Unit,
    val onAbortRebaseConflict: (PendingRebaseConflictResolution) -> Unit,
    val onLeaveRebaseConflictAsIs: (PendingRebaseConflictResolution) -> Unit,
    val worktrees: LocalWorktreeActions,
    val forceArchive: ForceArchiveWorktreeActions,
)

internal data class LocalWorktreeActions(
    val onOpenWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    val onArchiveWorktree: (repoRootPath: String, worktreePath: String) -> Unit,
    val onCreateWorktree: CreateWorktreeCallback,
    val onRebaseOntoParent: (repoRootPath: String, worktreePath: String, parentBranch: String) -> Unit,
)

internal data class ForceArchiveWorktreeActions(
    val onConfirm: (repoRootPath: String, worktreePath: String) -> Unit,
    val onDismiss: () -> Unit,
)

internal enum class CreateWorktreeMode {
    NEW,
    EXISTING,
}

internal data class ExistingBranchDiscoveryUiState(
    val repoRootPath: String = "",
    val branches: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

internal data class PendingCreateWorktree(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val baseCommitIsh: String? = null,
    val targetBranch: String = "",
    val mode: CreateWorktreeMode = CreateWorktreeMode.NEW,
    val existingBranchQuery: String = "",
    val selectedExistingBranch: String? = null,
)

internal data class PendingUseUnrelatedExistingBranch(
    val repoRootPath: String,
    val baseWorktreePath: String,
    val baseBranch: String,
    val targetBranch: String,
)

internal data class PendingRebaseConflictResolution(
    val repoRootPath: String,
    val worktreePath: String,
    val parentBranch: String,
)

internal data class PendingArchive(
    val repoRootPath: String,
    val worktreePath: String,
)

internal fun createWorktreeDialogState(
    repoRootPath: String,
    worktree: LocalWorktreeUiState,
): PendingCreateWorktree = PendingCreateWorktree(
    repoRootPath = repoRootPath,
    baseWorktreePath = worktree.path,
    baseBranch = worktree.branch,
    baseCommitIsh = worktree.baseCommitHash,
)

internal fun createRepositoryWorktreeDialogState(
    repoRootPath: String,
    baseWorktreePath: String,
    baseBranch: String,
): PendingCreateWorktree = PendingCreateWorktree(
    repoRootPath = repoRootPath,
    baseWorktreePath = baseWorktreePath,
    baseBranch = baseBranch,
)

internal fun submitCreateWorktreeDialog(
    state: PendingCreateWorktree,
    onCreateWorktree: CreateWorktreeCallback,
) {
    onCreateWorktree(state)
}

internal fun filterExistingBranches(branches: List<String>, query: String): List<String> = branches
    .mapNotNull { branch ->
        fuzzyMatchRank(query, listOf(branch) + branch.split('/'))?.let { rank ->
            RankedExistingBranch(branch, rank)
        }
    }
    .sortedWith(compareBy<RankedExistingBranch>({ it.rank.kind }, { it.rank.distance }, { it.branch }))
    .map(RankedExistingBranch::branch)

private data class RankedExistingBranch(
    val branch: String,
    val rank: FuzzyMatchRank,
)

internal fun confirmUseUnrelatedExistingBranchDialog(
    state: PendingUseUnrelatedExistingBranch,
    onConfirm: (PendingUseUnrelatedExistingBranch) -> Unit,
) {
    onConfirm(state)
}

internal fun dismissUseUnrelatedExistingBranchDialog(onDismiss: () -> Unit) {
    onDismiss()
}

internal fun abortRebaseConflictDialog(
    state: PendingRebaseConflictResolution,
    onAbort: (PendingRebaseConflictResolution) -> Unit,
) {
    onAbort(state)
}

internal fun leaveRebaseConflictAsIsDialog(
    state: PendingRebaseConflictResolution,
    onLeaveAsIs: (PendingRebaseConflictResolution) -> Unit,
) {
    onLeaveAsIs(state)
}
