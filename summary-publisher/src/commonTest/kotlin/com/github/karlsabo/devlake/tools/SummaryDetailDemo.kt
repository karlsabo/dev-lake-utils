package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.pagerduty.PagerDutyRestApi
import com.github.karlsabo.pagerduty.loadPagerDutyConfig
import com.github.karlsabo.text.TextSummarizerFake
import com.github.karlsabo.tools.createSummary
import com.github.karlsabo.tools.gitHubConfigPath
import com.github.karlsabo.tools.jiraConfigPath
import com.github.karlsabo.tools.loadUsersConfig
import com.github.karlsabo.tools.pagerDutyConfigPath
import com.github.karlsabo.tools.toSlackMarkup
import com.github.karlsabo.tools.toVerboseSlackMarkdown
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.days

fun main(args: Array<String>) {
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configFilePath: Path = configParameter?.let { Path(configParameter) } ?: summaryPublisherConfigPath

    runBlocking {
        val summaryConfig = loadSummaryPublisherConfig(configFilePath)

        val jiraApi =
            JiraRestApi(loadJiraConfig(jiraConfigPath))
        val gitHubApi = GitHubRestApi(loadGitHubConfig(gitHubConfigPath))
        val pagerDutyApi =
            if (summaryConfig.pagerDutyServiceIds.isNotEmpty()) PagerDutyRestApi(loadPagerDutyConfig(pagerDutyConfigPath)) else null
        val usersConfig = loadUsersConfig()!!

        val textSummarizer = TextSummarizerFake()
        val summaryLast7Days = createSummary(
            jiraApi,
            gitHubApi,
            summaryConfig.gitHubOrganizationIds,
            pagerDutyApi,
            summaryConfig.pagerDutyServiceIds,
            textSummarizer,
            summaryConfig.projects,
            7.days,
            usersConfig.users,
            summaryConfig.miscUserIds.map { userId -> usersConfig.users.first { it.id == userId } },
            summaryConfig.summaryName,
            summaryConfig.isMiscellaneousProjectIncluded
        )

        println("Summary:")
        if (summaryConfig.isTerseSummaryUsed)
            println(summaryLast7Days.toTerseSlackMarkup())
        else
            println(summaryLast7Days.toSlackMarkup())
        println()

        summaryLast7Days.projectSummaries.forEach {
            if (it.project.isVerboseMilestones)
                println(it.toVerboseSlackMarkdown())
            else
                println(it.toSlackMarkup())
            println()
        }
    }
}
