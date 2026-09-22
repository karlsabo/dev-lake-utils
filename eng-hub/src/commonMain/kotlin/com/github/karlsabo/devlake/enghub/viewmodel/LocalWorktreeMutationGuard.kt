package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.normalizedRepositoryPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Provides non-waiting, path-scoped exclusion for worktree mutations. */
internal class LocalWorktreeMutationGuard {
    private val guardedPaths = MutableStateFlow<Set<String>>(emptySet())

    fun tryAcquire(path: String): Lease? = tryAcquire(listOf(path))

    fun tryAcquire(paths: Collection<String>): Lease? {
        val normalizedPaths = paths
            .map(String::normalizedRepositoryPath)
            .filter(String::isNotEmpty)
            .toSet()
        return normalizedPaths.takeIf(Set<String>::isNotEmpty)?.let(::acquireNormalizedPaths)
    }

    private fun acquireNormalizedPaths(paths: Set<String>): Lease? {
        while (true) {
            val currentPaths = guardedPaths.value
            if (currentPaths.any(paths::contains)) return null
            if (guardedPaths.compareAndSet(currentPaths, currentPaths + paths)) return Lease(paths)
        }
    }

    internal inner class Lease(
        private val paths: Set<String>,
    ) {
        private val active = MutableStateFlow(true)

        fun release() {
            if (active.compareAndSet(expect = true, update = false)) {
                guardedPaths.update { currentPaths -> currentPaths - paths }
            }
        }
    }
}
