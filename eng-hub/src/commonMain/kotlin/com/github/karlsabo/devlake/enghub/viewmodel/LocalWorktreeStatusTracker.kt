package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryUiState
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeCheckout
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState

internal class LocalWorktreeStatusTracker(
    private val state: EngHubViewModelState,
) {
    fun publish(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        worktreePath: String,
        branch: String,
        isDirty: Boolean,
    ): Boolean = publish(normalizedRepoRootPath, request, Target(worktreePath, branch), isDirty)

    /** Continuous checkout identity permits slow results across refreshes, but not collapse or replacement. */
    fun publishCheckout(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        row: LocalWorktreeUiState,
        isDirty: Boolean,
    ): Boolean = publish(normalizedRepoRootPath, request, Target(row.path, row.branch, row.checkout), isDirty)

    private fun publish(
        root: String,
        request: LocalRepositoryWorktreeRequest,
        target: Target,
        isDirty: Boolean,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == root && target.ownsRows(it, request)
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        worktrees = currentRepository.worktrees.map { row ->
                            if (target.matches(row)) row.copy(isDirty = isDirty) else row
                        },
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }

    private class Target(
        path: String,
        val branch: String,
        val checkout: LocalWorktreeCheckout? = null,
    ) {
        private val normalizedPath = path.normalizedRepositoryPath()

        fun ownsRows(repository: LocalRepositoryUiState, request: LocalRepositoryWorktreeRequest): Boolean {
            if (repository.statusRequest === request) return true
            return repository.statusRequest != null && checkout != null &&
                repository.worktrees.any { it.checkout === checkout }
        }

        fun matches(row: LocalWorktreeUiState): Boolean {
            val sameRow = row.path.normalizedRepositoryPath() == normalizedPath && row.branch == branch
            return sameRow && (checkout == null || row.checkout === checkout)
        }
    }
}
