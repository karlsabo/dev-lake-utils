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
import me.tatarka.inject.annotations.Inject

class EngHubGitHubServices @Inject constructor(
    val pullRequestSearchApi: GitHubPullRequestSearchApi,
    val notificationApi: GitHubNotificationApi,
    val pullRequestReviewApi: GitHubPullRequestReviewApi,
    val pullRequestSummaryApi: GitHubPullRequestSummaryApi,
    val notificationService: GitHubNotificationService,
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
}

class EngHubWorktreeServices @Inject constructor(
    val gitWorktreeApi: GitWorktreeApi,
    val worktreeSetupCoordinator: WorktreeSetupCoordinator,
    val directoryPicker: DirectoryPicker,
    val configWriter: EngHubConfigWriter,
)

class EngHubDesktopServices @Inject constructor(
    val launcher: DesktopLauncher,
)
