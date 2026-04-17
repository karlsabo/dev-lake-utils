package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.metrics.model.UserMetrics
import com.github.karlsabo.devlake.metrics.service.SlackMessage
import com.github.karlsabo.dto.User
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.linearConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import me.tatarka.inject.annotations.Inject

data class UserMetricPublisherDependencies @Inject constructor(
    val previewDependencies: UserMetricPublisherPreviewDependencies,
    val messagePublisher: UserMetricMessagePublisher,
) {
    val usersConfig: UsersConfig
        get() = previewDependencies.usersConfig

    val projectManagementApi: ProjectManagementApi
        get() = previewDependencies.projectManagementApi

    val gitHubApi: GitHubApi
        get() = previewDependencies.gitHubApi

    val metricsBuilder: UserMetricsBuilder
        get() = previewDependencies.metricsBuilder
}

data class UserMetricPublisherPreviewDependencies @Inject constructor(
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

fun interface UserMetricMessagePublisher {
    suspend fun publishMessage(message: SlackMessage): Boolean
}

internal typealias UserMetricPublisherComponentFactory = (
    UserMetricPublisherConfig,
    UsersConfig,
    LinearApiRestConfig,
    GitHubApiRestConfig,
) -> UserMetricPublisherComponent

internal fun loadUserMetricPublisherDependencies(
    config: UserMetricPublisherConfig,
    loadUsersConfig: () -> UsersConfig? = ::loadUsersConfig,
    loadLinearApiConfig: () -> LinearApiRestConfig = { loadLinearConfig(linearConfigPath) },
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    componentFactory: UserMetricPublisherComponentFactory = ::createUserMetricPublisherComponent,
): UserMetricPublisherDependencies {
    val usersConfig = requireNotNull(loadUsersConfig()) { "Users config is required" }
    val linearApiConfig = loadLinearApiConfig()
    val gitHubApiConfig = loadGitHubApiConfig()

    return componentFactory(config, usersConfig, linearApiConfig, gitHubApiConfig).dependencies
}
