package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.tools.service.ZapierProjectSummary
import com.github.karlsabo.devlake.tools.service.ZapierService
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.config.loadLinearConfig
import com.github.karlsabo.pagerduty.PagerDutyRestApi
import com.github.karlsabo.pagerduty.loadPagerDutyConfig
import com.github.karlsabo.text.TextSummarizerOpenAi
import com.github.karlsabo.text.loadTextSummarizerOpenAiNoSecrets
import com.github.karlsabo.text.toTextSummarizerOpenAiConfig
import com.github.karlsabo.tools.createSummary
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.linearConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.textSummarizerConfigPath
import kotlin.time.Duration.Companion.days

data class SummaryPublisherDependencies(
    val summaryBuilder: SummaryBuilder,
    val summaryPublisher: SummaryMessagePublisher,
)

fun interface SummaryBuilder {
    suspend fun createSummary(): MultiProjectSummary
}

fun interface SummaryMessagePublisher {
    suspend fun publishSummary(summary: ZapierProjectSummary): Boolean
}

typealias SummaryPublisherDependencyProvider = (SummaryPublisherConfig) -> SummaryPublisherDependencies

val defaultSummaryPublisherDependencyProvider: SummaryPublisherDependencyProvider = { config ->
    val usersConfig = requireNotNull(loadUsersConfig()) { "Users config is required" }
    val projectManagementApi = LinearRestApi(loadLinearConfig(linearConfigPath))
    val gitHubApi = GitHubRestApi(loadGitHubConfig(gitHubConfigPath))
    val pagerDutyApi = PagerDutyRestApi(loadPagerDutyConfig(pagerDutyConfigPath))
    val textSummarizer = TextSummarizerOpenAi(
        requireNotNull(loadTextSummarizerOpenAiNoSecrets(textSummarizerConfigPath)) {
            "Text summarizer config is required"
        }.toTextSummarizerOpenAiConfig()
    )
    val miscUsers = config.miscUserIds.map { userId ->
        usersConfig.users.first { it.id == userId }
    }

    SummaryPublisherDependencies(
        summaryBuilder = {
            createSummary(
                projectManagementApi = projectManagementApi,
                gitHubApi = gitHubApi,
                gitHubOrganizationIds = config.gitHubOrganizationIds,
                pagerDutyApi = if (config.pagerDutyServiceIds.isNotEmpty()) pagerDutyApi else null,
                pagerDutyServiceIds = config.pagerDutyServiceIds,
                textSummarizer = textSummarizer,
                projects = config.projects,
                duration = 7.days,
                users = usersConfig.users,
                miscUsers = miscUsers,
                summaryName = config.summaryName,
                isMiscellaneousProjectIncluded = config.isMiscellaneousProjectIncluded,
            )
        },
        summaryPublisher = { summary ->
            ZapierService.sendSummary(summary, config.zapierSummaryUrl)
        },
    )
}
