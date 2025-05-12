package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.createSummary
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.dto.toTerseSlackMarkup
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.devlake.toSlackMarkdown
import com.github.karlsabo.devlake.toVerboseSlackMarkdown
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.text.TextSummarizerFake
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.days

fun main() = runBlocking {
    val textSummarizer = TextSummarizerFake()
    val summaryConfig = loadSummaryPublisherConfig()
    val dataSourceConfigNoSecrets = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)
    DataSourceManagerDb(dataSourceConfigNoSecrets!!.toDataSourceDbConfig()).use { dataSourceManager ->
        val summaryLast7Days = createSummary(
            dataSourceManager.getOrCreateDataSource(),
            textSummarizer,
            summaryConfig.projects,
            7.days,
            loadUserAndTeamConfig()!!.users,
            summaryConfig.summaryName,
        )

        println("Terse summary:")
        println(summaryLast7Days.toTerseSlackMarkup())
        println()

        summaryLast7Days.projectSummaries.forEach {
            if (it.project.isVerboseMilestones)
                println(it.toVerboseSlackMarkdown())
            else
                println(it.toSlackMarkdown())
            println()
        }

//        summaryLast7Days.projectSummaries.forEach { project ->
//            println("Project: ${project.project.title}")
//            println("Issue count = ${project.issues.size}")
//            println("changelogs: ")
//            project.issueChangeLogs.forEach { comment -> println("\tChangelog: $comment") }
//
//        }
    }
}
