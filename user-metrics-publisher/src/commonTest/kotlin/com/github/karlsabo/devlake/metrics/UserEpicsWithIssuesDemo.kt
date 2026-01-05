package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.jira.Issue
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.isCompleted
import com.github.karlsabo.jira.isIssueOrBug
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.jira.toPlainText
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.min
import kotlin.time.measureTime

/**
 * Demo that pulls all the epics that a user contributed to from Jira and lists the issues they completed under each epic.
 *
 * 1. Find all issues a user was the assignee of
 * 2. Keep a set of all the issues that had children (Epics)
 * 3. For each epic, find all issues under it
 * 4. Calculate statistics (tickets completed by user/total tickets in epic, percentage)
 * 5. Print out the epics and their issues with statistics
 */
fun main(args: Array<String>): Unit = runBlocking {
    val userId =
        args.find { it.startsWith("--user=") }?.substringAfter("=") ?: throw Exception("No --user=username provided")

    val jiraApi = JiraRestApi(loadJiraConfig(jiraConfigPath))

    println("Finding epics and their issues for user: $userId")

    val executionTime = measureTime {
        val startDate = LocalDateTime(2025, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC)
        val userIssues = jiraApi.getIssuesResolved(
            userId,
            startDate,
            Clock.System.now()
        )
        println("Found ${userIssues.size} issues assigned to user")

        if (userIssues.isEmpty()) {
            println("No issues found for user $userId")
            return@measureTime
        }

        val allParentIssues = mutableSetOf<Issue>()
        val processedIssueIds = mutableSetOf<String>()

        fun findAllParentIssues(issues: List<Issue>) {
            val parentIssueIds = issues
                .mapNotNull { it.fields.parent?.id }
                .filter { it !in processedIssueIds }
                .distinct()

            if (parentIssueIds.isEmpty()) return

            processedIssueIds.addAll(parentIssueIds)

            val parentIssues = runBlocking { jiraApi.getIssues(parentIssueIds) }

            allParentIssues.addAll(parentIssues)

            findAllParentIssues(parentIssues)
        }

        findAllParentIssues(userIssues)

        val parents =
            allParentIssues.filter { !it.isIssueOrBug() }.sortedBy { it.fields.resolutionDate ?: it.fields.created }

        println("# Parents the user contributed to:")

        if (parents.isEmpty()) {
            println("No parents found for this user")
        } else {
            val userIssuesByParentId = userIssues.groupBy { it.fields.parent?.id }

            parents.sortedBy { it.fields.issueType?.name }.forEach { epic ->
                val type = epic.fields.issueType?.name ?: "Unknown"
                val title = epic.fields.summary ?: "Untitled"
                epic.htmlUrl ?: "No URL available"
                val key = epic.key
                val date = epic.fields.resolutionDate ?: epic.fields.created

                // Get all issues under this epic
                val allChildIssues = runBlocking { jiraApi.getChildIssues(listOf(key)) }
                    .filter { it.isIssueOrBug() }

                // Get completed issues under this epic
                val completedChildIssues = allChildIssues.filter { it.isCompleted() }

                // Get user's completed issues under this epic
                val userCompletedIssues = userIssuesByParentId[epic.id] ?: emptyList()

                // Calculate statistics
                val userCompletedCount = userCompletedIssues.size
                val totalCompletedCount = completedChildIssues.size
                val percentageByUser = if (totalCompletedCount > 0) {
                    (userCompletedCount.toDouble() / totalCompletedCount.toDouble() * 100).toInt()
                } else {
                    0
                }

                if (userCompletedCount == 0) return@forEach

                println("* [$type] ($key) $date $title ")
                println("  * $userCompletedCount/$totalCompletedCount $percentageByUser%")

                // Print user's completed issues under this epic
                userCompletedIssues.forEach { issue ->
                    issue.fields.resolutionDate?.takeIf { it >= startDate } ?: return@forEach
                    println("  * ${issue.key} - ${issue.fields.summary ?: "Untitled"}")
                    issue.fields.description?.toPlainText().let { description ->
                        // Truncate description if it's too long
                        val truncatedDescription = if ((description?.length ?: 0) > 100) {
                            description?.substring(0, min(200, description.length)) + "..."
                        } else {
                            description
                        }
                        println("    * Details:")
                        println("      `````")
                        truncatedDescription?.split(Regex("""\R+"""))?.forEach {
                            println("      $it")
                        }
                        println("      `````")
                    }
                }
            }
        }
    }

    println("\nExecution time: $executionTime")
}
