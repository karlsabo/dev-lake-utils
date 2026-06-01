package com.github.karlsabo.devlake.enghub

import com.github.karlsabo.devlake.enghub.viewmodel.EngHubGitHubServices
import com.github.karlsabo.devlake.enghub.viewmodel.EngHubViewModel
import com.github.karlsabo.git.GitWorktreeApi
import com.github.karlsabo.git.GitWorktreeService
import com.github.karlsabo.git.WorktreeSetupCoordinator
import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.notifications.NotificationIgnoreStore
import com.github.karlsabo.notifications.SqlDelightNotificationIgnoreStore
import com.github.karlsabo.system.DesktopLauncher
import com.github.karlsabo.system.DesktopLauncherService
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

object EngHubScope

@ContributesTo(EngHubScope::class)
interface EngHubBindings {

    @Provides
    fun provideGitHubRestApi(gitHubApiConfig: GitHubApiRestConfig): GitHubRestApi = GitHubRestApi(gitHubApiConfig)

    @Provides
    fun provideGitHubNotificationService(
        gitHubRestApi: GitHubRestApi,
    ): GitHubNotificationService = GitHubNotificationService(gitHubRestApi)

    @Provides
    fun provideEngHubGitHubServices(
        gitHubRestApi: GitHubRestApi,
        notificationService: GitHubNotificationService,
    ): EngHubGitHubServices = EngHubGitHubServices(gitHubRestApi, notificationService)

    @Provides
    fun provideGitWorktreeApi(): GitWorktreeApi = GitWorktreeService()

    @Provides
    fun provideWorktreeSetupCoordinator(
        gitWorktreeApi: GitWorktreeApi,
    ): WorktreeSetupCoordinator = WorktreeSetupCoordinator(gitWorktreeApi = gitWorktreeApi)

    @Provides
    fun provideDesktopLauncher(): DesktopLauncher = DesktopLauncherService()

    @Provides
    fun provideDirectoryPicker(): DirectoryPicker = createDirectoryPicker()

    @Provides
    fun provideEngHubConfigWriter(): EngHubConfigWriter = EngHubConfigWriter(::saveEngHubConfig)

    @Provides
    fun provideNotificationIgnoreStore(): NotificationIgnoreStore = SqlDelightNotificationIgnoreStore()
}

@MergeComponent(EngHubScope::class)
@SingleIn(EngHubScope::class)
abstract class EngHubComponent(
    @get:Provides val config: EngHubConfig,
    @get:Provides val gitHubApiConfig: GitHubApiRestConfig,
) {
    abstract val viewModel: EngHubViewModel
}

@CreateComponent
expect fun createEngHubComponent(
    config: EngHubConfig,
    gitHubApiConfig: GitHubApiRestConfig,
): EngHubComponent
