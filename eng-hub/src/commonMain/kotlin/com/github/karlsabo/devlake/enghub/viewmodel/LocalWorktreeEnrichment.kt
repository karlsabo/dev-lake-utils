package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import com.github.karlsabo.git.WorktreeParentBranchOutcomes

internal class LocalWorktreeEnrichment(
    private val queuedWorktrees: List<LocalWorktreeUiState>,
    private val parentOutcomes: WorktreeParentBranchOutcomes? = null,
    private val originLookup: Result<String?> = Result.success(null),
    private val needsRebaseByChildBranch: Map<String, Boolean> = emptyMap(),
) {
    val worktrees: List<LocalWorktreeUiState>
        get() = mergeInto(queuedWorktrees)

    // Lookup outcomes must be interpreted again on every CAS attempt, against the current rows.
    fun mergeInto(current: List<LocalWorktreeUiState>): List<LocalWorktreeUiState> {
        val outcomes = parentOutcomes ?: return current.withEnrichmentFrom(queuedWorktrees)
        val queuedByPath = queuedWorktrees.associateBy { it.path.normalizedRepositoryPath() }
        val visibleBranches = current.mapTo(mutableSetOf()) { it.branch }
        return current.map { row ->
            val queued = queuedByPath[row.path.normalizedRepositoryPath()]
            if (queued == null || queued.branch != row.branch || queued.baseCommitHash != row.baseCommitHash) {
                row
            } else {
                resolve(row, outcomes, visibleBranches)
            }
        }
    }

    fun retaining(worktrees: List<LocalWorktreeUiState>): LocalWorktreeEnrichment = LocalWorktreeEnrichment(
        worktrees,
        parentOutcomes,
        originLookup,
        needsRebaseByChildBranch,
    )

    private fun resolve(
        current: LocalWorktreeUiState,
        outcomes: WorktreeParentBranchOutcomes,
        visibleBranches: Set<String>,
    ): LocalWorktreeUiState {
        val canUpdateFromOrigin = if (originLookup.isFailure) {
            current.canUpdateFromOrigin
        } else {
            current.branch == originLookup.getOrNull()
        }
        val parentUnresolved = current.branch in outcomes.unresolvedBranches
        val candidateParent = if (parentUnresolved) current.parentBranch else outcomes.parents[current.branch]
        val parent = candidateParent?.takeIf { it in visibleBranches && !canUpdateFromOrigin }
        val needsRebase = if (parentUnresolved) {
            current.needsRebase
        } else {
            needsRebaseByChildBranch[current.branch] == true
        }
        return current.copy(
            canUpdateFromOrigin = canUpdateFromOrigin,
            parentBranch = parent,
            needsRebase = parent != null && needsRebase,
            integrationTargetBranch = integrationTarget(current, parent, canUpdateFromOrigin),
        )
    }

    private fun integrationTarget(
        current: LocalWorktreeUiState,
        parent: String?,
        canUpdateFromOrigin: Boolean,
    ): String? = when {
        canUpdateFromOrigin -> null

        parent != null -> parent

        current.branch == "(detached)" -> null

        originLookup.isSuccess -> originLookup.getOrNull()?.takeIf { it.isNotBlank() }

        // A target belonging to a removed parent is not an independently known origin fallback.
        current.parentBranch == null -> current.integrationTargetBranch

        else -> null
    }
}
