package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.accessor.Issue
import com.github.karlsabo.devlake.accessor.IssueAccessorDb
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import kotlinx.coroutines.runBlocking
import kotlin.time.measureTime

/**
 * Demo that prints all issues a user was assigned to where the eventual parent is a specified key.
 *
 * 1. Find all issues with a parent issue-key - navigating all the way up
 * 2. Filter those issues to that user
 * 3. Print the issue
 *    - title and then description in markdown format
 *    - So the title is a list, and then the description is in a nested list.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val userId =
        args.find { it.startsWith("--user=") }?.substringAfter("=") ?: throw Exception("No --user=username provided")
    val parentKey =
        args.find { it.startsWith("--parent=") }?.substringAfter("=")
            ?: throw Exception("No --parent=issueKey provided")

    val dataSourceConfig = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)!!
    val dataSourceManager = DataSourceManagerDb(dataSourceConfig.toDataSourceDbConfig())
    val dataSource = dataSourceManager.getOrCreateDataSource()

    val issueAccessor = IssueAccessorDb(dataSource)

    println("Finding issues for user: $userId with parent key: $parentKey")

    val executionTime = measureTime {
        // Get the parent issue by key
        val parentIssue = issueAccessor.getIssuesByKey(parentKey)
        if (parentIssue == null) {
            println("Parent issue with key $parentKey not found")
            return@measureTime
        }

        // Find all issues that have this parent (directly or indirectly)
        val allIssuesUnderParent = issueAccessor.getAllChildIssues(listOf(parentIssue.id))
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

        userIssues.sortedBy { it.createdDate }.forEach { issue ->
            val title = issue.title ?: "Untitled"
            val description = issue.description ?: "No description"
            val key = issue.issueKey
            val url = issue.url ?: "No URL available"

            println("* $title [$key]($url)")
            println("  * ```$description```")
            println()
        }

        println("\nTotal: ${userIssues.size} issues")
    }

    println("\nExecution time: $executionTime")
}

/**
 * Recursively finds all issues under a parent issue.
 */
private fun findAllIssuesUnderParent(issueAccessor: IssueAccessorDb, parentIssue: Issue): Set<Issue> {
    val result = mutableSetOf<Issue>()
    val processedIssueIds = mutableSetOf<String>()

    fun findAllChildIssues(issue: Issue) {
        if (issue.id in processedIssueIds) return
        processedIssueIds.add(issue.id)

        val childIssues = issueAccessor.getIssuesByParentIssueId(issue.id)
        result.addAll(childIssues)

        childIssues.forEach { childIssue ->
            findAllChildIssues(childIssue)
        }
    }

    findAllChildIssues(parentIssue)
    return result
}
