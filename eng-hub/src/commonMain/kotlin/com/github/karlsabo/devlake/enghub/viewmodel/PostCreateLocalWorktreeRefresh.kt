package com.github.karlsabo.devlake.enghub.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PostCreateLocalWorktreeRefresh(
    val job: Job,
    val state: PostCreateLocalWorktreeRefreshState,
) {
    suspend fun cancelWaitingOrAwaitStartedRefresh(): Boolean {
        val refreshStarted = state.cancelWaitingOrReportStarted()
        if (!refreshStarted) job.cancelAndJoin()
        return refreshStarted
    }
}

internal class PostCreateLocalWorktreeRefreshState {
    private val guard = Mutex()
    private var status = PostCreateLocalWorktreeRefreshStatus.WAITING

    suspend fun markRefreshStarted(): Boolean = guard.withLock {
        when (status) {
            PostCreateLocalWorktreeRefreshStatus.WAITING -> {
                status = PostCreateLocalWorktreeRefreshStatus.STARTED
                true
            }

            PostCreateLocalWorktreeRefreshStatus.STARTED -> true

            PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START -> false
        }
    }

    suspend fun cancelWaitingOrReportStarted(): Boolean = guard.withLock {
        when (status) {
            PostCreateLocalWorktreeRefreshStatus.WAITING -> {
                status = PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START
                false
            }

            PostCreateLocalWorktreeRefreshStatus.STARTED -> true

            PostCreateLocalWorktreeRefreshStatus.CANCELLED_BEFORE_START -> false
        }
    }
}

private enum class PostCreateLocalWorktreeRefreshStatus {
    WAITING,
    STARTED,
    CANCELLED_BEFORE_START,
}
