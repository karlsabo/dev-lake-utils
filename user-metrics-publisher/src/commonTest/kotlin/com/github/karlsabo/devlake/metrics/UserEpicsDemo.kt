package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.loadJiraConfig
import com.github.karlsabo.projectmanagement.ProjectIssue
import com.github.karlsabo.projectmanagement.isIssueOrBug
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime

/**
 * Demo that pulls all the epics that a user contributed to from Jira.
 *
 * 1. Find all issues a user was the assignee of
 * 2. Keep a set of all the issues that had children (Epics)
 * 3. Print out the title and a link to those Epics for the user
 */
fun main(args: Array<String>): Unit = runBlocking {
    val userId =
        args.find { it.startsWith("--user=") }?.substringAfter("=") ?: throw Exception("No --user=username provided")

    val jiraApi = JiraRestApi(loadJiraConfig(jiraConfigPath))

    println("Finding epics for user: $userId")

    val executionTime = measureTime {
        val userIssues = jiraApi.getIssuesResolved(userId, Clock.System.now().minus((4 * 365).days), Clock.System.now())
        println("Found ${userIssues.size} issues assigned to user")

        if (userIssues.isEmpty()) {
            println("No issues found for user $userId")
            return@measureTime
        }

        val allParentIssues = mutableSetOf<ProjectIssue>()
        val processedIssueKeys = mutableSetOf<String>()

        fun findAllParentIssues(issues: List<ProjectIssue>) {
            val parentIssueKeys = issues
                .mapNotNull { it.parentKey }
                .filter { it !in processedIssueKeys }
                .distinct()

            if (parentIssueKeys.isEmpty()) return

            processedIssueKeys.addAll(parentIssueKeys)

            val parentIssues = runBlocking { jiraApi.getIssues(parentIssueKeys) }

            allParentIssues.addAll(parentIssues)

            findAllParentIssues(parentIssues)
        }

        findAllParentIssues(userIssues)

        val epics =
            allParentIssues.filter { !it.isIssueOrBug() }.sortedBy { it.completedAt ?: it.createdAt }

        println("\nEpics the user contributed to:")
        println("========================================")

        if (epics.isEmpty()) {
            println("No epics found for this user")
        } else {
            epics.sortedBy { it.issueType }.forEach { issue ->
                val type = issue.issueType ?: "Unknown"
                val title = issue.title ?: "Untitled"
                val url = issue.url ?: "No URL available"
                val key = issue.key
                val date = issue.completedAt ?: issue.createdAt

                println("[$type] $date $title ($key)")
                println("URL: $url")
                println("----------------------------------------")
            }

            println("\nTotal: ${epics.size} epics")
        }
    }

    println("\nExecution time: $executionTime")
}
