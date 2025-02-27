package com.github.karlsabo.devlake.tools

import com.github.karlsabo.devlake.createSummary
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.devlake.dto.toSlackMarkup
import com.github.karlsabo.devlake.loadUserAndTeamConfig
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import com.github.karlsabo.text.TextSummarizerFake
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration.Companion.days

class SummaryTest {
    @Test
    fun testPrintSummary() = runBlocking {
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
            val slackMarkDown = summaryLast7Days?.toSlackMarkup() ?: "* Failed to load summary"
            println(slackMarkDown)
        }
    }
}
