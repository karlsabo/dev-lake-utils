package com.github.karlsabo.dto

import com.github.karlsabo.pagerduty.PagerDutyIncident
import com.github.karlsabo.tools.formatting.toSlackMarkup
import com.github.karlsabo.tools.formatting.toTerseSlackMarkdown
import com.github.karlsabo.tools.model.ProjectSummary
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class MultiProjectSummary(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val summaryName: String,
    val projectSummaries: List<ProjectSummary>,
    val pagerDutyAlerts: List<PagerDutyIncident>?,
)

fun MultiProjectSummary.toTerseSlackMarkup(): String {
    val slackSummary = StringBuilder()
    slackSummary.appendLine("*$summaryName update $startDate - $endDate*")
    slackSummary.appendLine()
    projectSummaries.joinToString("") {
        it.toTerseSlackMarkdown()
    }.let { slackSummary.append(it) }

    if (pagerDutyAlerts != null) {
        slackSummary.appendLine("📟 *Pager Duty Alerts*")
        val alerts = mutableListOf<String>()
        pagerDutyAlerts.forEach {
            alerts.add("- <${it.htmlUrl}|${it.incidentNumber}>: ${it.description}")
        }
        if (alerts.isEmpty()) alerts.add("- No pages! 🎉")
        alerts.forEach { slackSummary.appendLine(it) }
    }
    return slackSummary.toString()
}

fun MultiProjectSummary.toSlackMarkup(): String {
    val slackSummary = StringBuilder()
    slackSummary.appendLine()
    slackSummary.appendLine("*$summaryName update $startDate - $endDate*")
    slackSummary.appendLine()
    slackSummary.appendLine("━━━━━━━━━━━━━━━━━━")
    projectSummaries.forEach {
        slackSummary.append(it.toSlackMarkup())
        slackSummary.appendLine("━━━━━━━━━━━━━━━━━━")
    }
    if (pagerDutyAlerts != null) {
        slackSummary.appendLine()
        slackSummary.appendLine("📟 *Pager Duty Alerts*")
        slackSummary.appendLine()
        val alerts = mutableListOf<String>()
        pagerDutyAlerts.forEach {
            alerts.add("- <${it.htmlUrl}|${it.incidentNumber}>: ${it.description}")
        }
        if (alerts.isEmpty()) alerts.add("- No pages! 🎉")
        alerts.forEach { slackSummary.appendLine(it) }
    }
    return slackSummary.toString()
}
