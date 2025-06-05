package com.github.karlsabo.github

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

        // Get PR count for the past year
        val now = Clock.System.now()
        val currentYear = now.toString().substring(0, 4)
        val startOfTheYear = Instant.parse("${currentYear}-01-01T00:00:00Z")

        runBlocking {
            // Get PR count for the past year
            val prCount = githubApi.getPullRequestCount(username, startOfTheYear, now)
            println("PR count for $username in the past year: $prCount")

            // Get PRs for the last week
            val oneWeekAgo = now.minus(7.days)
            val recentPRs = githubApi.getClosedPullRequests(username, oneWeekAgo, now)

            println("\nPRs closed by $username in the last week (${recentPRs.size} total):")
            recentPRs.forEach { pr ->
                println("- #${pr.number}: ${pr.title}")
                println("  URL: ${pr.htmlUrl}")
                println("  Created: ${formatDate(pr.createdAt)}")
                println("  Closed: ${pr.closedAt?.let { formatDate(it) } ?: "N/A"}")
                println("  Repository: ${pr.repository.fullName}")
                println("  Changes: +${pr.additions ?: 0} -${pr.deletions ?: 0} (${pr.changedFiles ?: 0} files)")
                println()
            }
        }

    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun formatDate(instant: Instant): String {
    return instant.toString().substring(0, 10) // Simple format: YYYY-MM-DD
}
