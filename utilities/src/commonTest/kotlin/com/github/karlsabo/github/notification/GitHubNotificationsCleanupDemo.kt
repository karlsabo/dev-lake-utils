package com.github.karlsabo.github.notification

import com.github.karlsabo.github.GitHubNotificationService
import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.NotificationProcessingResult
import com.github.karlsabo.github.config.loadGitHubConfig
import com.github.karlsabo.tools.gitHubConfigPath
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.io.files.Path

fun main(args: Array<String>) {
    println("GitHub Notifications Demo")

    val configPath = Path(gitHubConfigPath)

    try {
        println("Loading GitHub configuration from $configPath")
        val config = loadGitHubConfig(configPath)
        val githubApi = GitHubRestApi(config)
        val service = GitHubNotificationService(githubApi)

        runBlocking {
            val results = service.processAllNotifications()
            println("Found ${results.size} notifications")

            if (results.isEmpty()) {
                println("No notifications to show.")
                return@runBlocking
            }

            results.forEachIndexed { index, result ->
                printResult(index, result)
            }
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun printResult(index: Int, result: NotificationProcessingResult) {
    val n = result.notification
    println("\n#${index + 1}")
    println("  Thread ID: ${n.id}")
    println("  Repository: ${n.repository.fullName}")
    println("  Subject: ${n.subject.title} (${n.subject.type})")
    println("  Reason: ${n.reason}")
    println("  Unread: ${n.unread}")
    println("  Updated: ${formatDate(n.updatedAt)}")
    n.lastReadAt?.let { println("  Last read: ${formatDate(it)}") }

    when (result) {
        is NotificationProcessingResult.Processed -> {
            result.pullRequestStatus?.let { println("  PR Status: ${it.name.lowercase()}") }
            result.pullRequestUrl?.let { println("  PR URL: $it") }
            result.actions.forEach { action ->
                println("  Action: ${action.description}")
            }
        }

        is NotificationProcessingResult.Failed -> {
            if (n.subject.type.equals("PullRequest", ignoreCase = true)) {
                println("  PR Status: failed to fetch (${result.error})")
            }
        }
    }
}

private fun formatDate(instant: Instant): String {
    return instant.toString().replace('T', ' ').removeSuffix("Z")
}
