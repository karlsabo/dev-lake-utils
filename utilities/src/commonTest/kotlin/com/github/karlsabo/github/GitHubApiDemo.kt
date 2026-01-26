package com.github.karlsabo.github

import com.github.karlsabo.github.config.loadGitHubConfig
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.days

/**
 * Demo for GitHub API functionality.
 * This demo shows how to:
 * 1. Load GitHub configuration
 * 2. Get PR count for a user for the past year
 * 3. Get PRs for a user for the last week
 * 4. Get merged PRs by author ID within a date range
 * 5. Get count of merged PRs by author ID within a date range
 */
fun main(args: Array<String>) {
    println("GitHub API Demo")
    val configParameter = args.find { it.startsWith("--config=") }?.substringAfter("=")
    val configPath = Path(configParameter!!)

    try {
        // Load GitHub configuration
        println("Loading GitHub configuration from $configPath")
        val config = loadGitHubConfig(Path(configPath))
        val githubApi = GitHubRestApi(config)

        // Get username from args or use default
        val username =
            args.find { it.startsWith("--user=") }?.substringAfter("=")
                ?: throw Exception("No --user=username provided")
        println("Using GitHub username: $username")

        val organizations = args.find { it.startsWith("--orgs=") }?.substringAfter("=")?.split(",") ?: emptyList()

        // Get PR count for the past year
        val now = Clock.System.now()
        val currentYear = now.toString().substring(0, 4)
        val startOfTheYear = Instant.parse("${currentYear}-01-01T00:00:00Z")

        runBlocking {
            // Get PR count for the past year
            val prCount = githubApi.getMergedPullRequestCount(username, organizations, startOfTheYear, now)
            println("PR count for $username in the past year: $prCount")

            // Get PRs for the last week
            val oneWeekAgo = now.minus(7.days)
            val recentPRs = githubApi.getMergedPullRequests(username, organizations, oneWeekAgo, now)

            println("\nPRs closed by $username in the last week (${recentPRs.size} total):")
            recentPRs.forEach { pr ->
                println("- #${pr.number}: ${pr.title}")
                println("  URL: ${pr.htmlUrl}")
                println("  Created: ${formatDate(pr.createdAt)}")
                println("  Closed: ${pr.closedAt?.let { formatDate(it) } ?: "N/A"}")
                println()
            }

            // Get merged PRs by author ID within a date range
            // For this demo, we'll use the same user and date range as above
            // In a real scenario, you would get the user ID from somewhere else
            val userId = recentPRs.firstOrNull()?.user?.login ?: username
            val mergedPRs = githubApi.getMergedPullRequests(userId, organizations, oneWeekAgo, now)

            println("\nPRs merged by user ID $userId in the last week (${mergedPRs.size} total):")
            mergedPRs.forEach { pr ->
                println("- #${pr.number}: ${pr.title}")
                println("  URL: ${pr.htmlUrl}")
                println("  Created: ${formatDate(pr.createdAt)}")
                println("  Merged: ${pr.pullRequest?.mergedAt?.let { formatDate(it) } ?: "N/A"}")
                println()
            }

            val mergedPRsCount = githubApi.getMergedPullRequestCount(userId, organizations, oneWeekAgo, now)
            println("\nCount of PRs merged by user ID $userId in the last week: $mergedPRsCount")
        }

    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun formatDate(instant: Instant): String {
    return instant.toString().substring(0, 10) // Simple format: YYYY-MM-DD
}
