package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.metrics.service.MetricsService
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

object UserMetricPublisherScope

@ContributesTo(UserMetricPublisherScope::class)
interface UserMetricPublisherBindings {

    @Provides
    fun provideProjectManagementApi(linearApiConfig: LinearApiRestConfig): ProjectManagementApi {
        return LinearRestApi(linearApiConfig)
    }

    @Provides
    fun provideGitHubApi(gitHubApiConfig: GitHubApiRestConfig): GitHubApi = GitHubRestApi(gitHubApiConfig)

    @Provides
    fun provideMetricsBuilder(): UserMetricsBuilder {
        return UserMetricsBuilder { user, organizationIds, projectManagementApi, gitHubApi ->
            MetricsService.createUserMetrics(user, organizationIds, projectManagementApi, gitHubApi)
        }
    }
}

@MergeComponent(UserMetricPublisherScope::class)
@SingleIn(UserMetricPublisherScope::class)
abstract class UserMetricPublisherComponent(
    @get:Provides val usersConfig: UsersConfig,
    @get:Provides val linearApiConfig: LinearApiRestConfig,
    @get:Provides val gitHubApiConfig: GitHubApiRestConfig,
) {
    abstract val previewDependencies: UserMetricPublisherPreviewDependencies
}

@CreateComponent
expect fun createUserMetricPublisherComponent(
    usersConfig: UsersConfig,
    linearApiConfig: LinearApiRestConfig,
    gitHubApiConfig: GitHubApiRestConfig,
): UserMetricPublisherComponent
