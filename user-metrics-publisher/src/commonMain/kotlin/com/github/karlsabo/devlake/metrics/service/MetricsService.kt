package com.github.karlsabo.devlake.metrics.service

import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days

object MetricsService {
    suspend fun createUserMetrics(
        user: User,
        organizationIds: List<String>,
        projectManagementApi: ProjectManagementApi,
        gitHubApi: GitHubApi,
    ): UserMetrics {
        val now = System.now()
        val startOfYear = startOfCurrentYear()
        val weekAgo = now.minus(7.days)

        val pullRequestsPastWeek = gitHubApi.getMergedPullRequests(
            user.gitHubId!!,
            organizationIds,
            weekAgo,
            now,
        )

        val prCountYtd = gitHubApi.getMergedPullRequestCount(
            user.gitHubId!!,
            organizationIds,
            startOfYear,
            now,
        )

        val prReviewCountYtd = gitHubApi.getPullRequestReviewCount(
            user.gitHubId!!,
            organizationIds,
            startOfYear,
            now,
        )

        val userId = user.jiraId ?: user.id
        val issuesClosedPastWeek = projectManagementApi.getIssuesResolved(userId, weekAgo, now)
        val issuesCountYtd = projectManagementApi.getIssuesResolvedCount(userId, startOfYear, now)

        return UserMetrics(
            userId = user.id,
            pullRequestsPastWeek = pullRequestsPastWeek,
            pullRequestsYearToDateCount = prCountYtd,
            prReviewCountYtd = prReviewCountYtd,
            issuesClosedLastWeek = issuesClosedPastWeek,
            issuesClosedYearToDateCount = issuesCountYtd,
        )
    }

    private fun startOfCurrentYear(): Instant {
        return System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            .run { Instant.parse("${year}-01-01T00:00:00Z") }
    }
}
