package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.jira.JiraRestApi
import com.github.karlsabo.jira.config.loadJiraConfig
import com.github.karlsabo.tools.jiraConfigPath
import kotlinx.coroutines.runBlocking
import kotlin.time.measureTime

/**
 * Demo that prints all issues a user was assigned to where the eventual parent is a specified key.
 *
 * 1. Find all issues with a parent issue-key - navigating all the way up
 * 2. Filter those issues to that user
 * 3. Print the issue
 *    - title and then description in Markdown format
 *    - So the title is a list, and then the description is in a nested list.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val userId =
        args.find { it.startsWith("--user=") }?.substringAfter("=") ?: throw Exception("No --user=username provided")
    val parentKey =
        args.find { it.startsWith("--parent=") }?.substringAfter("=")
            ?: throw Exception("No --parent=issueKey provided")

    val projectManagementApi = JiraRestApi(loadJiraConfig(jiraConfigPath))

    println("Finding issues for user: $userId with parent key: $parentKey")

    val executionTime = measureTime {
        // Find all issues that have this parent (directly or indirectly)
        val allIssuesUnderParent = projectManagementApi.getChildIssues(listOf(parentKey))
        println("Found ${allIssuesUnderParent.size} issues under parent $parentKey")

        // Filter to issues assigned to the specified user
        val userIssues = allIssuesUnderParent.filter { it.assigneeId == userId }
        println("Found ${userIssues.size} issues assigned to user $userId under parent $parentKey")

        if (userIssues.isEmpty()) {
            println("No issues found for user $userId under parent $parentKey")
            return@measureTime
        }

        // Print the issues in the required format
        println("\nIssues assigned to user $userId under parent $parentKey:")
        println("========================================")

        userIssues.sortedBy { it.createdAt }.forEach { issue ->
            val title = issue.title ?: "Untitled"
            val description = issue.description ?: "No description"
            val key = issue.key
            val url = issue.url ?: "No URL available"

            println("* $title [$key]($url)")
            println("  * ```$description```")
            println()
        }

        println("\nTotal: ${userIssues.size} issues")
    }

    println("\nExecution time: $executionTime")
}
