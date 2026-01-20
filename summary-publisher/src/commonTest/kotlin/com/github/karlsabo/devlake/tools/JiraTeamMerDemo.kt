package com.github.karlsabo.devlake.tools

import com.github.karlsabo.jira.CustomFieldFilter
import com.github.karlsabo.jira.Issue
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.jira.toPlainText
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.days

fun main(args: Array<String>) {
    val daysBack = args.find { it.startsWith("--days=") }
        ?.substringAfter("=")?.toIntOrNull() ?: 30

    val configPath = args.find { it.startsWith("--config=") }
        ?.substringAfter("=")?.let { Path(it) }
        ?: jiraConfigPath

    val teams = args.find { it.startsWith("--teams=") }
        ?.substringAfter("=")
        ?.split(",")
        ?.map { it.trim() }
        ?: throw Exception("--teams is required as a parameter")

    runBlocking {
        val jiraApi = JiraRestApi(loadJiraConfig(configPath))

        val filter = CustomFieldFilter(
            fieldId = "R&D Team",
            values = teams
        )

        val now = Clock.System.now()
        val startDate = now.minus(daysBack.days)

        println("Fetching Epics and Themes closed in the last $daysBack days...")
        println("Teams: ${teams.joinToString(", ")}")
        println()

        val issues = jiraApi.getIssuesByCustomFieldFilter(
            issueTypes = listOf("Epic", "Theme"),
            customFieldFilter = filter,
            resolvedAfter = startDate,
        )

        if (issues.isEmpty()) {
            println("No Epics or Themes found matching the criteria.")
            return@runBlocking
        }

        println("Found ${issues.size} Epics/Themes")
        println()

        printMarkdownReport(issues, teams, startDate, now)
    }
}

private fun printMarkdownReport(
    issues: List<Issue>,
    teams: List<String>,
    startDate: kotlinx.datetime.Instant,
    endDate: kotlinx.datetime.Instant,
) {
    val startDateStr = startDate.toLocalDateTime(TimeZone.UTC).date.toString()
    val endDateStr = endDate.toLocalDateTime(TimeZone.UTC).date.toString()

    println("# Team Report - Completed Epics & Themes")
    println()
    println("**Period:** $startDateStr to $endDateStr")
    println("**Teams:** ${teams.joinToString(", ")}")
    println()
    println("---")
    println()

    issues.forEach { issue ->
        val type = issue.fields.issueType?.name ?: "Unknown"
        val key = issue.key
        val summary = issue.fields.summary ?: "No summary"
        val url = issue.htmlUrl
        val resolvedDate = issue.fields.resolutionDate
            ?.toLocalDateTime(TimeZone.UTC)?.date?.toString() ?: "N/A"

        println("## [$key]($url) - $summary")
        println()
        println("**Type:** $type | **Resolved:** $resolvedDate")
        println()

        val description = issue.fields.description.toPlainText()
        if (!description.isNullOrBlank()) {
            val truncatedDesc = if (description.length > 500) {
                description.take(500) + "..."
            } else {
                description
            }
            println("> ${truncatedDesc.replace("\n", "\n> ")}")
            println()
        }

        println("---")
        println()
    }
}
