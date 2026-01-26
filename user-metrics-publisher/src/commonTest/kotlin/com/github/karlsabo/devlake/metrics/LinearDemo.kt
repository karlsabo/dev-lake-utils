package com.github.karlsabo.devlake.metrics

import com.github.karlsabo.linear.LinearRestApi
import com.github.karlsabo.linear.loadLinearConfig
import com.github.karlsabo.tools.linearConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.measureTime

/**
 * Demo that pulls issues assigned to a user from Linear.
 *
 * Usage: ./gradlew :user-metrics-publisher:runLinearDemo --args="--user=<linear-user-id>"
 *
 * The Linear user ID can be found in the Linear app URL when viewing a user's profile,
 * or via the Linear API.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val userId =
        args.find { it.startsWith("--user=") }?.substringAfter("=")
            ?: throw Exception("No --user=<linear-user-id> provided")

    val weeksBack = args.find { it.startsWith("--weeks=") }?.substringAfter("=")?.toIntOrNull() ?: 30

    val linearApi = LinearRestApi(loadLinearConfig(linearConfigPath))

    println("Finding issues resolved by user: $userId (last $weeksBack weeks)")

    val executionTime = measureTime {
        val endDate = Clock.System.now()
        val startDate = endDate.minus((weeksBack * 7).days)

        val resolvedIssues = linearApi.getIssuesResolved(userId, startDate, endDate)
        println("Found ${resolvedIssues.size} resolved issues\n")

        if (resolvedIssues.isEmpty()) {
            println("No resolved issues found for user $userId in the specified time range")
            return@measureTime
        }

        println("Resolved Issues:")
        println("========================================")

        resolvedIssues.sortedByDescending { it.completedAt }.forEach { issue ->
            val title = issue.title
            val identifier = issue.key
            val url = issue.url
            val completedAt = issue.completedAt
            val state = issue.status ?: "Unknown"

            println("[$identifier] $title")
            println("  State: $state")
            println("  Completed: $completedAt")
            println("  URL: $url")
            issue.parentKey?.let { parentKey ->
                println("  Parent: $parentKey")
            }
            println("----------------------------------------")
        }

        println("\nTotal: ${resolvedIssues.size} resolved issues")
    }

    println("\nExecution time: $executionTime")
}
