package com.github.karlsabo.devlake.metrics.service

import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.dto.User
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import me.tatarka.inject.annotations.Inject

open class UserMetricsService @Inject constructor() {
    open suspend fun createUserMetrics(
        user: User,
        organizationIds: List<String>,
        projectManagementApi: ProjectManagementApi,
        gitHubApi: GitHubApi,
    ): UserMetrics {
        return MetricsService.createUserMetrics(user, organizationIds, projectManagementApi, gitHubApi)
    }
}
