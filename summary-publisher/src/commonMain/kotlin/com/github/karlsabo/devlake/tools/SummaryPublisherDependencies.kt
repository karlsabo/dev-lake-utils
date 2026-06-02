package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.tools.service.SummaryBuilderService
import com.github.karlsabo.devlake.tools.service.SummaryMessagePublisherService
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubPullRequestSearchApi
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
    fun provideProjectManagementApi(
        linearApiConfig: LinearApiRestConfig,
    ): ProjectManagementApi = LinearRestApi(linearApiConfig)

    @Provides
    fun provideGitHubApi(
        gitHubApiConfig: GitHubApiRestConfig,
    ): GitHubPullRequestSearchApi = GitHubRestApi(gitHubApiConfig)

    @Provides
    fun providePagerDutyApi(
        pagerDutyApiConfig: PagerDutyApiRestConfig,
    ): PagerDutyApi = PagerDutyRestApi(pagerDutyApiConfig)

    @Provides
    fun provideTextSummarizer(
        textSummarizerConfig: TextSummarizerOpenAiConfig,
    ): TextSummarizer = TextSummarizerOpenAi(textSummarizerConfig)
}

data class SummaryPublisherComponentInputs(
    val config: SummaryPublisherConfig,
    val usersConfig: UsersConfig,
    val linearApiConfig: LinearApiRestConfig,
    val gitHubApiConfig: GitHubApiRestConfig,
    val pagerDutyApiConfig: PagerDutyApiRestConfig,
    val textSummarizerConfig: TextSummarizerOpenAiConfig,
)

@MergeComponent(SummaryPublisherScope::class)
@SingleIn(SummaryPublisherScope::class)
abstract class SummaryPublisherComponent(
    @get:Provides val inputs: SummaryPublisherComponentInputs,
) {
    @Provides
    fun provideConfig(): SummaryPublisherConfig = inputs.config

    @Provides
    fun provideUsersConfig(): UsersConfig = inputs.usersConfig

    @Provides
    fun provideLinearApiConfig(): LinearApiRestConfig = inputs.linearApiConfig

    @Provides
    fun provideGitHubApiConfig(): GitHubApiRestConfig = inputs.gitHubApiConfig

    @Provides
    fun providePagerDutyApiConfig(): PagerDutyApiRestConfig = inputs.pagerDutyApiConfig

    @Provides
    fun provideTextSummarizerConfig(): TextSummarizerOpenAiConfig = inputs.textSummarizerConfig

    abstract val dependencies: SummaryPublisherDependencies
}

@CreateComponent
expect fun createSummaryPublisherComponent(inputs: SummaryPublisherComponentInputs): SummaryPublisherComponent

internal fun interface SummaryPublisherComponentFactory {
    fun create(inputs: SummaryPublisherComponentInputs): SummaryPublisherComponent
}

internal data class SummaryPublisherConfigLoaders(
    val loadUsersConfig: () -> UsersConfig?,
    val loadLinearApiConfig: () -> LinearApiRestConfig,
    val loadGitHubApiConfig: () -> GitHubApiRestConfig,
    val loadPagerDutyApiConfig: () -> PagerDutyApiRestConfig,
    val loadTextSummarizerConfig: () -> TextSummarizerOpenAiConfig,
) {
    companion object {
        fun defaults() = SummaryPublisherConfigLoaders(
            loadUsersConfig = ::loadUsersConfig,
            loadLinearApiConfig = { loadLinearConfig(linearConfigPath) },
            loadGitHubApiConfig = { loadGitHubConfig(gitHubConfigPath) },
            loadPagerDutyApiConfig = { loadPagerDutyConfig(pagerDutyConfigPath) },
            loadTextSummarizerConfig = {
                requireNotNull(loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)) {
                    "Text summarizer config is required"
                }.toTextSummarizerOpenAiConfig()
            },
        )
    }
}

internal fun loadSummaryPublisherDependencies(
    config: SummaryPublisherConfig,
    configLoaders: SummaryPublisherConfigLoaders = SummaryPublisherConfigLoaders.defaults(),
    componentFactory: SummaryPublisherComponentFactory =
        SummaryPublisherComponentFactory(::createSummaryPublisherComponent),
): SummaryPublisherDependencies {
    val inputs = SummaryPublisherComponentInputs(
        config = config,
        usersConfig = requireNotNull(configLoaders.loadUsersConfig()) { "Users config is required" },
        linearApiConfig = configLoaders.loadLinearApiConfig(),
        gitHubApiConfig = configLoaders.loadGitHubApiConfig(),
        pagerDutyApiConfig = configLoaders.loadPagerDutyApiConfig(),
        textSummarizerConfig = configLoaders.loadTextSummarizerConfig(),
    )

    return componentFactory.create(inputs).dependencies
}
