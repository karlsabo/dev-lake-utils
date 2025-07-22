package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.createSummary
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.dto.toSlackMarkup
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
            null,
            textSummarizer,
            summaryConfig.projects,
            7.days,
            loadUserAndTeamConfig()!!.users,
            summaryConfig.summaryName,
            summaryConfig.isTerseSummaryUsed,
            summaryConfig.isPagerDutyIncluded,
        )
        val slackMarkDown = summaryLast7Days.toSlackMarkup()
        println(slackMarkDown)
    }
}
