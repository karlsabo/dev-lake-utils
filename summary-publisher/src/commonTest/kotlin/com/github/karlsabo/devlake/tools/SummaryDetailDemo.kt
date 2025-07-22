package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.*
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.dto.toTerseSlackMarkup
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.text.TextSummarizerFake
import com.github.karlsabo.tools.createSummary
import com.github.karlsabo.tools.getApplicationDirectory
import com.github.karlsabo.tools.toSlackMarkup
import com.github.karlsabo.tools.toVerboseSlackMarkdown
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.days

fun main(args: Array<String>) {
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configFilePath: Path = configParameter?.let { Path(configParameter) } ?: summaryPublisherConfigPath

    runBlocking {
        val jiraApi =
            JiraRestApi(loadJiraConfig(Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "jira-rest-config.json")))

        val textSummarizer = TextSummarizerFake()
        val summaryConfig = loadSummaryPublisherConfig(configFilePath)
        val dataSourceConfigNoSecrets = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)
        DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
            val summaryLast7Days = createSummary(
                dataSourceManager.getOrCreateDataSource(),
                jiraApi,
                textSummarizer,
                summaryConfig.projects,
                7.days,
                loadUserAndTeamConfig()!!.users,
                summaryConfig.summaryName,
                summaryConfig.isMiscellaneousProjectIncluded,
                summaryConfig.isPagerDutyIncluded,
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
}
