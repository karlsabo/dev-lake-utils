package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubApi
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.GitHubApiRestConfig
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.config.LinearApiRestConfig
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.pagerduty.PagerDutyApiRestConfig
import com.github.karlsabo.pagerduty.PagerDutyRestApi
import com.github.karlsabo.pagerduty.loadPagerDutyConfig
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.text.TextSummarizerOpenAi
import com.github.karlsabo.text.TextSummarizerOpenAiConfig
import com.github.karlsabo.text.loadTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.toTextSummarizerOpenAiConfig
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.linearConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.textSummarizerConfigPath
import com.github.karlsabo.devlake.tools.service.SummaryBuilderService
import com.github.karlsabo.devlake.tools.service.SummaryMessagePublisherService
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

data class SummaryPublisherDependencies @Inject constructor(
    val summaryBuilder: SummaryBuilderService,
    val summaryPublisher: SummaryMessagePublisherService,
)

object SummaryPublisherScope

@ContributesTo(SummaryPublisherScope::class)
interface SummaryPublisherBindings {

    @Provides
    fun provideProjectManagementApi(linearApiConfig: LinearApiRestConfig): ProjectManagementApi {
        return LinearRestApi(linearApiConfig)
    }

    @Provides
    fun provideGitHubApi(gitHubApiConfig: GitHubApiRestConfig): GitHubApi = GitHubRestApi(gitHubApiConfig)

    @Provides
    fun providePagerDutyApi(pagerDutyApiConfig: PagerDutyApiRestConfig): PagerDutyApi {
        return PagerDutyRestApi(pagerDutyApiConfig)
    }

    @Provides
    fun provideTextSummarizer(textSummarizerConfig: TextSummarizerOpenAiConfig): TextSummarizer {
        return TextSummarizerOpenAi(textSummarizerConfig)
    }
}

@MergeComponent(SummaryPublisherScope::class)
@SingleIn(SummaryPublisherScope::class)
abstract class SummaryPublisherComponent(
    @get:Provides val config: SummaryPublisherConfig,
    @get:Provides val usersConfig: UsersConfig,
    @get:Provides val linearApiConfig: LinearApiRestConfig,
    @get:Provides val gitHubApiConfig: GitHubApiRestConfig,
    @get:Provides val pagerDutyApiConfig: PagerDutyApiRestConfig,
    @get:Provides val textSummarizerConfig: TextSummarizerOpenAiConfig,
) {
    abstract val dependencies: SummaryPublisherDependencies
}

@CreateComponent
expect fun createSummaryPublisherComponent(
    config: SummaryPublisherConfig,
    usersConfig: UsersConfig,
    linearApiConfig: LinearApiRestConfig,
    gitHubApiConfig: GitHubApiRestConfig,
    pagerDutyApiConfig: PagerDutyApiRestConfig,
    textSummarizerConfig: TextSummarizerOpenAiConfig,
): SummaryPublisherComponent

internal typealias SummaryPublisherComponentFactory = (
    SummaryPublisherConfig,
    UsersConfig,
    LinearApiRestConfig,
    GitHubApiRestConfig,
    PagerDutyApiRestConfig,
    TextSummarizerOpenAiConfig,
) -> SummaryPublisherComponent

internal fun loadSummaryPublisherDependencies(
    config: SummaryPublisherConfig,
    loadUsersConfig: () -> UsersConfig? = ::loadUsersConfig,
    loadLinearApiConfig: () -> LinearApiRestConfig = { loadLinearConfig(linearConfigPath) },
    loadGitHubApiConfig: () -> GitHubApiRestConfig = { loadGitHubConfig(gitHubConfigPath) },
    loadPagerDutyApiConfig: () -> PagerDutyApiRestConfig = { loadPagerDutyConfig(pagerDutyConfigPath) },
    loadTextSummarizerConfig: () -> TextSummarizerOpenAiConfig = {
        requireNotNull(loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)) {
            "Text summarizer config is required"
        }.toTextSummarizerOpenAiConfig()
    },
    componentFactory: SummaryPublisherComponentFactory = ::createSummaryPublisherComponent,
): SummaryPublisherDependencies {
    val usersConfig = requireNotNull(loadUsersConfig()) { "Users config is required" }
    val linearApiConfig = loadLinearApiConfig()
    val gitHubApiConfig = loadGitHubApiConfig()
    val pagerDutyApiConfig = loadPagerDutyApiConfig()
    val textSummarizerConfig = loadTextSummarizerConfig()

    return componentFactory(
        config,
        usersConfig,
        linearApiConfig,
        gitHubApiConfig,
        pagerDutyApiConfig,
        textSummarizerConfig,
    ).dependencies
}
