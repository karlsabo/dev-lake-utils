package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.flow.update

internal class LocalRepositoryExpansionTracker(
    private val state: EngHubViewModelState,
) {
    fun markInFlight(normalizedRepoRootPath: String): Boolean {
        while (true) {
            val currentPaths = state.localRepositoryExpansionsInFlight.value
            if (normalizedRepoRootPath in currentPaths) return false
            if (state.localRepositoryExpansionsInFlight.compareAndSet(
                    currentPaths,
                    currentPaths + normalizedRepoRootPath,
                )
            ) {
                return true
            }
        }
    }

    fun clear(normalizedRepoRootPath: String) {
        state.localRepositoryExpansionsInFlight.update { it - normalizedRepoRootPath }
    }

    fun isInFlight(normalizedRepoRootPath: String): Boolean {
        val inFlightPaths = state.localRepositoryExpansionsInFlight.value
        return normalizedRepoRootPath in inFlightPaths
    }
}
