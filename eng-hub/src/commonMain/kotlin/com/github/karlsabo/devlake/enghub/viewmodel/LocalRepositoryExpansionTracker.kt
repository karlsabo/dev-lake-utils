package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlinx.coroutines.flow.update

internal class LocalRepositoryExpansionTracker(
    private val state: EngHubViewModelState,
) {
    fun start(normalizedRepoRootPath: String): Any? {
        val request = Any()
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories
                .firstOrNull { it.path.normalizedRepoPath() == normalizedRepoRootPath }
                ?.takeUnless { it.isExpanded || it.expansionRequest != null }
                ?: return null

            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isExpanded = true,
                        isLoading = true,
                        expansionRequest = request,
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return request
        }
    }

    fun collapse(normalizedRepoRootPath: String) {
        state.localRepositories.update { repositories ->
            repositories.map { repository ->
                if (repository.path.normalizedRepoPath() == normalizedRepoRootPath) {
                    repository.copy(
                        isExpanded = false,
                        isLoading = false,
                        expansionRequest = null,
                    )
                } else {
                    repository
                }
            }
        }
    }

    fun publishDiscovered(
        normalizedRepoRootPath: String,
        request: Any,
        worktrees: List<LocalWorktreeUiState>,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath && it.expansionRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(worktrees = worktrees)
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }

    fun complete(
        normalizedRepoRootPath: String,
        request: Any,
        worktrees: List<LocalWorktreeUiState>? = null,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepoPath() == normalizedRepoRootPath && it.expansionRequest === request
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        expansionRequest = null,
                        worktrees = worktrees ?: currentRepository.worktrees,
                    )
                } else {
                    currentRepository
                }
            }
            if (state.localRepositories.compareAndSet(repositories, updatedRepositories)) return true
        }
    }
}
