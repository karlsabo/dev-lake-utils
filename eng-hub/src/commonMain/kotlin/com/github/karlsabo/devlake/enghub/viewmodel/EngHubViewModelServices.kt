package com.github.karlsabo.devlake.enghub.viewmodel

import com.github.karlsabo.devlake.enghub.DirectoryPicker
import com.github.karlsabo.devlake.enghub.EngHubConfigWriter
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubNotificationApi
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.GitHubPullRequestReviewApi
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.github.GitHubPullRequestSummaryApi
import com.github.karlsabo.system.DesktopLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

class EngHubGitHubServices @Inject constructor(
    val pullRequestSearchApi: GitHubPullRequestSearchApi,
    val notificationApi: GitHubNotificationApi,
    val pullRequestReviewApi: GitHubPullRequestReviewApi,
    val pullRequestSummaryApi: GitHubPullRequestSummaryApi,
    val notificationService: GitHubNotificationService,
    private val closeServices: () -> Unit = {},
) {
    constructor(
        api: GitHubApi,
        notificationService: GitHubNotificationService,
    ) : this(
        pullRequestSearchApi = api,
        notificationApi = api,
        pullRequestReviewApi = api,
        pullRequestSummaryApi = api,
        notificationService = notificationService,
    )

    internal fun close() = closeServices()
}

internal data class CommittedGitHubAccess(
    val services: EngHubGitHubServices,
    val isReady: Boolean,
)

internal class GitHubServiceActionTracker(
    private val coroutineScope: CoroutineScope,
) {
    private val state = MutableStateFlow(GitHubServiceActionState())

    fun launch(
        services: EngHubGitHubServices,
        action: suspend (EngHubGitHubServices) -> Unit,
    ): Job {
        val job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { action(services) }
        register(services, job)
        job.invokeOnCompletion { complete(services, job) }
        job.start()
        return job
    }

    fun retire(services: EngHubGitHubServices) {
        while (true) {
            val current = state.value
            if (services in current.closedServices) return
            val shouldClose = current.activeJobs[services].isNullOrEmpty()
            val updated = current.copy(
                retiredServices = current.retiredServices + services,
                closedServices = if (shouldClose) current.closedServices + services else current.closedServices,
            )
            if (state.compareAndSet(current, updated)) {
                if (shouldClose) services.close()
                return
            }
        }
    }

    private fun register(services: EngHubGitHubServices, job: Job) {
        while (true) {
            val current = state.value
            check(services !in current.retiredServices) { "Cannot start an action with retired GitHub services" }
            val updated = current.copy(
                activeJobs = current.activeJobs + (services to current.activeJobs[services].orEmpty() + job),
            )
            if (state.compareAndSet(current, updated)) return
        }
    }

    private fun complete(services: EngHubGitHubServices, job: Job) {
        while (true) {
            val current = state.value
            val remainingJobs = current.activeJobs[services].orEmpty() - job
            val shouldClose = services in current.retiredServices &&
                remainingJobs.isEmpty() &&
                services !in current.closedServices
            val updatedJobs = if (remainingJobs.isEmpty()) {
                current.activeJobs - services
            } else {
                current.activeJobs + (services to remainingJobs)
            }
            val updated = current.copy(
                activeJobs = updatedJobs,
                closedServices = if (shouldClose) current.closedServices + services else current.closedServices,
            )
            if (state.compareAndSet(current, updated)) {
                if (shouldClose) services.close()
                return
            }
        }
    }
}

private data class GitHubServiceActionState(
    val activeJobs: Map<EngHubGitHubServices, Set<Job>> = emptyMap(),
    val retiredServices: Set<EngHubGitHubServices> = emptySet(),
    val closedServices: Set<EngHubGitHubServices> = emptySet(),
)

class EngHubWorktreeServices @Inject constructor(
    val gitWorktreeApi: GitWorktreeApi,
    val worktreeSetupCoordinator: WorktreeSetupCoordinator,
    val directoryPicker: DirectoryPicker,
    val configWriter: EngHubConfigWriter,
)

class EngHubDesktopServices @Inject constructor(
    val launcher: DesktopLauncher,
)
