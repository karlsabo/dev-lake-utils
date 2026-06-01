package com.github.karlsabo.devlake.tools.service

import com.github.karlsabo.devlake.tools.SummaryPublisherConfig
import com.github.karlsabo.dto.MultiProjectSummary
import com.github.karlsabo.dto.UsersConfig
import com.github.karlsabo.github.GitHubPullRequestSearchApi
import com.github.karlsabo.pagerduty.PagerDutyApi
import com.github.karlsabo.projectmanagement.ProjectManagementApi
import com.github.karlsabo.text.TextSummarizer
import com.github.karlsabo.tools.CreateSummaryRequest
import com.github.karlsabo.tools.SummaryOptions
import com.github.karlsabo.tools.SummarySources
import com.github.karlsabo.tools.createSummary
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.days

class SummaryBuilderService @Inject constructor(
    private val config: SummaryPublisherConfig,
    private val usersConfig: UsersConfig,
    private val projectManagementApi: ProjectManagementApi,
    private val gitHubApi: GitHubPullRequestSearchApi,
    private val pagerDutyApi: PagerDutyApi,
    private val textSummarizer: TextSummarizer,
) {
    suspend fun createSummary(): MultiProjectSummary {
        val miscUsers = config.miscUserIds.map { userId ->
            usersConfig.users.first { it.id == userId }
        }

        return createSummary(
            CreateSummaryRequest(
                sources = SummarySources(
                    projectManagementApi = projectManagementApi,
                    gitHubApi = gitHubApi,
                    gitHubOrganizationIds = config.gitHubOrganizationIds,
                    pagerDutyApi = if (config.pagerDutyServiceIds.isNotEmpty()) pagerDutyApi else null,
                    pagerDutyServiceIds = config.pagerDutyServiceIds,
                    textSummarizer = textSummarizer,
                ),
                projects = config.projects,
                duration = 7.days,
                users = usersConfig.users,
                miscUsers = miscUsers,
                options = SummaryOptions(
                    summaryName = config.summaryName,
                    isMiscellaneousProjectIncluded = config.isMiscellaneousProjectIncluded,
                ),
            ),
        )
    }
}
