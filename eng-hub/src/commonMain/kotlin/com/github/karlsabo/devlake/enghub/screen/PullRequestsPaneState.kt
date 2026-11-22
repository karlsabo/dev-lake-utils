package com.github.karlsabo.devlake.enghub.screen

import com.github.karlsabo.devlake.enghub.state.EngHubSettingsUiState
import com.github.karlsabo.devlake.enghub.state.PullRequestsResult
import com.github.karlsabo.git.WorktreePath
import com.github.karlsabo.git.WorktreeSetupStatus

internal data class PullRequestsPaneState(
    val result: PullRequestsResult?,
    val organizationIdsEmpty: Boolean,
    val setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
)

internal fun pullRequestsPaneState(
    pullRequests: PullRequestsResult?,
    settings: EngHubSettingsUiState,
    setupStatuses: Map<WorktreePath, WorktreeSetupStatus>,
) = PullRequestsPaneState(
    result = pullRequests,
    organizationIdsEmpty = settings.committedConfig.organizationIds.isEmpty() && !settings.gitLabAccessReady,
    setupStatuses = setupStatuses,
)
