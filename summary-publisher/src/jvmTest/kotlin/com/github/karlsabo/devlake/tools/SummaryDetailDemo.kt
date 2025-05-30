package com.github.karlsabo.devlake.tools

import com.github.karlsabo.Credentials
import com.github.karlsabo.devlake.*
import com.github.karlsabo.devlake.dto.toSlackMarkup
import com.github.karlsabo.devlake.dto.toTerseSlackMarkup
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.jira.JiraApiRest
import com.github.karlsabo.jira.JiraApiRestConfig
import com.github.karlsabo.jira.toPlainText
import com.github.karlsabo.text.TextSummarizerFake
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

@Serializable
data class JiraConfig(
    val domain: String,
    val username: String,
    val apiKeyPath: String,
)

@Serializable
data class JiraSecret(val jiraApiKey: String)

private val json = Json { ignoreUnknownKeys = true }

fun getJiraConfig(configFilePath: Path): JiraApiRestConfig {
    val config = SystemFileSystem.source(Path(configFilePath)).buffered().use { source ->
        json.decodeFromString<JiraConfig>(source.readText())
    }
    val secretConfig = SystemFileSystem.source(Path(config.apiKeyPath)).buffered().use { source ->
        json.decodeFromString<JiraSecret>(source.readText())
    }

    return JiraApiRestConfig(
        Credentials(
            config.username,
            secretConfig.jiraApiKey,
        ),
        config.domain,
    )
}

fun main(args: Array<String>) {
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configFilePath: Path = configParameter?.let { Path(configParameter) } ?: summaryPublisherConfigPath

    runBlocking {
        val jiraApi = JiraApiRest(getJiraConfig(Path(getApplicationDirectory(DEV_LAKE_APP_NAME), "jira-rest-config.json")))
        val epics = jiraApi.runJql("issue in portfolioChildIssuesOf(\"ENGWIDE-1032\") and type in (Epic) AND resolutiondate is EMPTY")
        println("Epics:")
        epics.forEach {
            println("${it.issueKey} ${it.title}")
            jiraApi.getRecentComments(it.issueKey, 1).forEach { comment ->
                println("${comment.author.displayName} (${comment.created}): ${comment.body.toPlainText()}")
            }
        }

        if(true) return@runBlocking

        val textSummarizer = TextSummarizerFake()
        val summaryConfig = loadSummaryPublisherConfig(configFilePath)
        val dataSourceConfigNoSecrets = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)
        DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
            val summaryLast7Days = createSummary(
                dataSourceManager.getOrCreateDataSource(),
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
