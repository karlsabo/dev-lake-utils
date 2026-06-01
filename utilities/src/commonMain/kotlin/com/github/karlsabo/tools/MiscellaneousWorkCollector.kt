package com.github.karlsabo.tools

import com.github.karlsabo.dto.User
import com.github.karlsabo.projectmanagement.ProjectIssue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration
import com.github.karlsabo.github.Issue as GitHubIssue

private val logger = KotlinLogging.logger {}

internal data class MiscWorkRequest(
    val users: List<User>,
    val sources: SummarySources,
    val duration: Duration,
)

internal data class MiscWorkAccumulator(
    val mutex: Mutex,
    val issues: MutableSet<ProjectIssue>,
    val pullRequests: MutableSet<GitHubIssue>,
)

internal suspend fun collectMiscellaneousWork(
    request: MiscWorkRequest,
    accumulator: MiscWorkAccumulator,
) = coroutineScope {
    request.users.flatMap { user ->
        listOf(
            async(Dispatchers.Default) { collectIssuesForUser(user, request, accumulator) },
            async(Dispatchers.Default) { collectPullRequestsForUser(user, request, accumulator) },
        )
    }.joinAll()
}

private suspend fun collectIssuesForUser(
    user: User,
    request: MiscWorkRequest,
    accumulator: MiscWorkAccumulator,
) {
    logger.info { "Pulling issues for user ${user.id}" }
    val issuesForUser = request.sources.projectManagementApi.getIssuesResolved(
        user,
        Clock.System.now().minus(request.duration),
        Clock.System.now(),
    )
    accumulator.mutex.withLock { accumulator.issues.addAll(issuesForUser) }
}

private suspend fun collectPullRequestsForUser(
    user: User,
    request: MiscWorkRequest,
    accumulator: MiscWorkAccumulator,
) {
    logger.info { "Pulling PRs for user ${user.id}" }
    val pullRequestsForUser = request.sources.gitHubApi.getMergedPullRequests(
        user.gitHubId!!,
        request.sources.gitHubOrganizationIds,
        Clock.System.now().minus(request.duration),
        Clock.System.now(),
    )
    accumulator.mutex.withLock { accumulator.pullRequests.addAll(pullRequestsForUser) }
}
