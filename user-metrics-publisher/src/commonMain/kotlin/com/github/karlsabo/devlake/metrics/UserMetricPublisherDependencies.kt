package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.devlake.metrics.service.MetricsService
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.linearConfigPath
import com.github.karlsabo.tools.loadUsersConfig

data class UserMetricPublisherDependencies(
    val usersConfig: UsersConfig,
    val projectManagementApi: ProjectManagementApi,
    val gitHubApi: GitHubApi,
    val metricsBuilder: UserMetricsBuilder,
)

fun interface UserMetricsBuilder {
    suspend fun createUserMetrics(
        user: User,
        organizationIds: List<String>,
        projectManagementApi: ProjectManagementApi,
        gitHubApi: GitHubApi,
    ): UserMetrics
}

typealias UserMetricPublisherDependencyProvider = (UserMetricPublisherConfig) -> UserMetricPublisherDependencies

val defaultUserMetricPublisherDependencyProvider: UserMetricPublisherDependencyProvider = {
    UserMetricPublisherDependencies(
        usersConfig = requireNotNull(loadUsersConfig()) { "Users config is required" },
        projectManagementApi = LinearRestApi(loadLinearConfig(linearConfigPath)),
        gitHubApi = GitHubRestApi(loadGitHubConfig(gitHubConfigPath)),
        metricsBuilder = UserMetricsBuilder { user, organizationIds, projectManagementApi, gitHubApi ->
            MetricsService.createUserMetrics(user, organizationIds, projectManagementApi, gitHubApi)
        },
    )
}
