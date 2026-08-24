package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import com.github.karlsabo.devlake.enghub.state.LocalRepositoryWorktreeRequest
import com.github.karlsabo.devlake.enghub.state.LocalWorktreeUiState
import kotlinx.coroutines.flow.update

internal class LocalRepositoryExpansionTracker(
    private val state: EngHubViewModelState,
) {
    fun start(normalizedRepoRootPath: String): LocalRepositoryWorktreeRequest? {
        val request = LocalRepositoryWorktreeRequest()
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories
                .firstOrNull { it.path.normalizedRepositoryPath() == normalizedRepoRootPath }
                ?.takeUnless { it.isExpanded }
                ?: return null

            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isExpanded = true,
                        isLoading = true,
                        operationRequest = request,
                        refreshRequest = null,
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
                if (repository.path.normalizedRepositoryPath() == normalizedRepoRootPath) {
                    repository.copy(
                        isExpanded = false,
                        isLoading = false,
                        operationRequest = null,
                        refreshRequest = null,
                    )
                } else {
                    repository
                }
            }
        }
    }

    fun publishDiscovered(
        normalizedRepoRootPath: String,
        request: LocalRepositoryWorktreeRequest,
        worktrees: List<LocalWorktreeUiState>,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    it.operationRequest === request &&
                    it.refreshRequest == null
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
        request: LocalRepositoryWorktreeRequest,
        worktrees: List<LocalWorktreeUiState>? = null,
    ): Boolean {
        while (true) {
            val repositories = state.localRepositories.value
            val repository = repositories.firstOrNull {
                it.path.normalizedRepositoryPath() == normalizedRepoRootPath &&
                    it.operationRequest === request &&
                    it.refreshRequest == null
            } ?: return false
            val updatedRepositories = repositories.map { currentRepository ->
                if (currentRepository === repository) {
                    currentRepository.copy(
                        isLoading = false,
                        operationRequest = null,
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
