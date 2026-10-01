package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest

internal class LocalWorktreeStatusTracker(
    private val state: EngHubViewModelState,
) {
    /**
     * Fills the dirty status of the published row identified by normalized path plus branch, but only while
     * [request] still owns those rows; a newer expansion, refresh, or collapse discards the result.
     */
    fun publish(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        worktreePath: String,
        branch: String,
        isDirty: Boolean,
    ): Boolean {
        val normalizedWorktreePath = worktreePath.normalizedRepositoryPath()
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    it.statusRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        worktrees = currentRepository.worktrees.withWorktreeStatus(
                            normalizedWorktreePath = normalizedWorktreePath,
                            branch = branch,
                            isDirty = isDirty,
                        ),
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }
}
