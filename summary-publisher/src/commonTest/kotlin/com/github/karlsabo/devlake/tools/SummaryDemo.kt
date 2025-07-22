package com.github.karlsabo.devlake.tools

import com.github.karlsabo.dto.toSlackMarkup
import com.github.karlsabo.text.TextSummarizerFake
import com.github.karlsabo.tools.createSummary
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.days

fun main() = runBlocking {
    val textSummarizer = TextSummarizerFake()
    val summaryConfig = loadSummaryPublisherConfig()
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
