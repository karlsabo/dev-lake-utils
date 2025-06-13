package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.devlake.accessor.Issue
import com.github.karlsabo.devlake.accessor.IssueAccessorDb
import com.github.karlsabo.devlake.accessor.isIssueOrBug
import com.github.karlsabo.devlake.devLakeDataSourceDbConfigPath
import com.github.karlsabo.ds.DataSourceManagerDb
import com.github.karlsabo.ds.loadDataSourceDbConfigNoSecrets
import com.github.karlsabo.ds.toDataSourceDbConfig
import kotlinx.coroutines.runBlocking
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

    val dataSourceConfig = loadDataSourceDbConfigNoSecrets(devLakeDataSourceDbConfigPath)!!
    val dataSourceManager = DataSourceManagerDb(dataSourceConfig.toDataSourceDbConfig())
    val dataSource = dataSourceManager.getOrCreateDataSource()

    val issueAccessor = IssueAccessorDb(dataSource)

    println("Finding epics for user: $userId")

    val executionTime = measureTime {
        val userIssues = issueAccessor.getIssuesByAssigneeId(userId)
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

            val parentIssues = issueAccessor.getIssuesById(parentIssueIds)

            allParentIssues.addAll(parentIssues)

            findAllParentIssues(parentIssues)
        }

        findAllParentIssues(userIssues)

        val epics = allParentIssues.filter { !it.isIssueOrBug() }.sortedBy { it.resolutionDate ?: it.createdDate }

        println("\nEpics the user contributed to:")
        println("========================================")

        if (epics.isEmpty()) {
            println("No epics found for this user")
        } else {
            epics.sortedBy { it.type }.forEach { issue ->
                val type = issue.type ?: "Unknown"
                val title = issue.title ?: "Untitled"
                val url = issue.url ?: "No URL available"
                val key = issue.issueKey
                val date = issue.resolutionDate ?: issue.createdDate

                println("[$type] $date $title ($key)")
                println("URL: $url")
                println("----------------------------------------")
            }

            println("\nTotal: ${epics.size} epics")
        }
    }

    println("\nExecution time: $executionTime")
}
