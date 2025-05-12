package com.github.karlsabo.devlake.dto

import com.github.karlsabo.devlake.ProjectSummary
import com.github.karlsabo.devlake.toSlackMarkdown
import com.github.karlsabo.devlake.toTerseSlackMarkdown
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class DevLakeSummary(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val summaryName: String,
    val projectSummaries: List<ProjectSummary>,
    val pagerDutyAlerts: List<PagerDutyAlert>,
)

fun DevLakeSummary.toTerseSlackMarkup(): String {
    val slackSummary = StringBuilder()
    slackSummary.appendLine()
    slackSummary.appendLine("*$summaryName update $startDate - $endDate*")
    slackSummary.appendLine()
    projectSummaries.forEach {
        slackSummary.appendLine(it.toTerseSlackMarkdown())
        slackSummary.appendLine()
    }
    slackSummary.appendLine()
    slackSummary.appendLine("📟 *Pager Duty Alerts*")
    val alerts = mutableListOf<String>()
    pagerDutyAlerts.forEach {
        alerts.add("- <${it.url}|${it.key}>: ${it.description}")
    }
    if (alerts.isEmpty()) alerts.add("- No pages! 🎉")
    alerts.forEach { slackSummary.appendLine(it) }
    return slackSummary.toString()
}

fun DevLakeSummary.toSlackMarkup(): String {
    val slackSummary = StringBuilder()
    slackSummary.appendLine()
    slackSummary.appendLine("*$summaryName update $startDate - $endDate*")
    slackSummary.appendLine()
    slackSummary.appendLine("━━━━━━━━━━━━━━━━━━")
    projectSummaries.forEach {
        slackSummary.appendLine(it.toSlackMarkdown())
        slackSummary.appendLine("━━━━━━━━━━━━━━━━━━")
    }
    slackSummary.appendLine()
    slackSummary.appendLine("📟 *Pager Duty Alerts*")
    slackSummary.appendLine()
    val alerts = mutableListOf<String>()
    pagerDutyAlerts.forEach {
        alerts.add("- <${it.url}|${it.key}>: ${it.description}")
    }
    if (alerts.isEmpty()) alerts.add("- No pages! 🎉")
    alerts.forEach { slackSummary.appendLine(it) }
    return slackSummary.toString()
}

fun DevLakeSummary.toMarkdown(): String {
    val markdownSummary = StringBuilder()
    markdownSummary.appendLine()
    markdownSummary.appendLine("# Identity and Access Management weekly update $startDate - ${endDate}")
    markdownSummary.appendLine()
    markdownSummary.appendLine()
    markdownSummary.appendLine("## Projects")
    markdownSummary.appendLine()
    projectSummaries.forEach {
        markdownSummary.appendLine(it.toSlackMarkdown())
    }
    markdownSummary.appendLine()

    markdownSummary.appendLine()
    markdownSummary.appendLine("## Pager Duty Alerts")
    markdownSummary.appendLine()
    val alerts = mutableListOf<String>()
    pagerDutyAlerts.forEach {
        alerts.add("""* [${it.key}](${it.url}): ${it.description}""")
    }
    if (alerts.isEmpty()) alerts.add("* No pages! :tada:")
    alerts.forEach { markdownSummary.appendLine(it) }

    return markdownSummary.toString()
}
