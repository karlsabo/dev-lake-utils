package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.jira.Issue
import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.isCompleted
import com.github.karlsabo.jira.isIssueOrBug
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
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
        val userIssues = jiraApi.getIssuesResolved(userId, Clock.System.now().minus((4 * 365).days), Clock.System.now())
        println("Found ${userIssues.size} issues assigned to user")

        if (userIssues.isEmpty()) {
            println("No issues found for user $userId")
            return@measureTime
        }

        val allParentIssues = mutableSetOf<Issue>()
        val processedIssueIds = mutableSetOf<String>()

        fun findAllParentIssues(issues: List<Issue>) {
            val parentIssueIds = issues
                .mapNotNull { it.parentIssueId }
                .filter { it !in processedIssueIds }
                .distinct()

            if (parentIssueIds.isEmpty()) return

            processedIssueIds.addAll(parentIssueIds)

            val parentIssues = runBlocking { jiraApi.getIssues(parentIssueIds) }

            allParentIssues.addAll(parentIssues)

            findAllParentIssues(parentIssues)
        }

        findAllParentIssues(userIssues)

        val epics = allParentIssues.filter { !it.isIssueOrBug() }.sortedBy { it.resolutionDate ?: it.createdDate }

        println("# Epics the user contributed to:")

        if (epics.isEmpty()) {
            println("No epics found for this user")
        } else {
            val userIssuesByParentId = userIssues.groupBy { it.parentIssueId }

            epics.sortedBy { it.type }.forEach { epic ->
                val type = epic.type ?: "Unknown"
                val title = epic.title ?: "Untitled"
                epic.url ?: "No URL available"
                val key = epic.issueKey
                val date = epic.resolutionDate ?: epic.createdDate

                println("* [$type] ($key) $date $title ")

                // Get all issues under this epic
                val allEpicIssues = runBlocking { jiraApi.getChildIssues(listOf(key)) }
                    .filter { it.isIssueOrBug() }

                // Get completed issues under this epic
                val completedEpicIssues = allEpicIssues.filter { it.isCompleted() }

                // Get user's completed issues under this epic
                val userCompletedIssues = userIssuesByParentId[epic.id] ?: emptyList()

                // Calculate statistics
                val userCompletedCount = userCompletedIssues.size
                val totalCompletedCount = completedEpicIssues.size
                val percentageByUser = if (totalCompletedCount > 0) {
                    (userCompletedCount.toDouble() / totalCompletedCount.toDouble() * 100).toInt()
                } else {
                    0
                }

                // Print statistics
                println("  * $userCompletedCount/$totalCompletedCount $percentageByUser%")

                // Print user's completed issues under this epic
                userCompletedIssues.forEach { issue ->
                    println("  * ${issue.issueKey} - ${issue.title ?: "Untitled"}")
                    issue.description?.let { description ->
                        // Truncate description if it's too long
                        val truncatedDescription = if (description.length > 100) {
                            description.substring(0, 97) + "..."
                        } else {
                            description
                        }
                        println("    * $truncatedDescription")
                    }
                }
            }
        }
    }

    println("\nExecution time: $executionTime")
}
