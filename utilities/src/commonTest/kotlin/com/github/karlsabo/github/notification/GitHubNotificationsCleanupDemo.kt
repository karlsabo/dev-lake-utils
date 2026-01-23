package com.github.karlsabo.github.notification

import com.github.karlsabo.github.GitHubRestApi
import com.github.karlsabo.github.Notification
import com.github.karlsabo.github.loadGitHubConfig
import com.github.karlsabo.tools.gitHubConfigPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Instant
import kotlinx.io.files.Path

/**
 * Demo that lists and cleans up notifications for the authenticated GitHub user.
 */
fun main(args: Array<String>) {
    println("GitHub Notifications Demo")

    val configPath = Path(gitHubConfigPath)

    try {
        println("Loading GitHub configuration from $configPath")
        val config = loadGitHubConfig(configPath)
        val githubApi = GitHubRestApi(config)

        runBlocking {
            val notifications = githubApi.listNotifications()
            println("Found ${notifications.size} notifications")

            if (notifications.isEmpty()) {
                println("No notifications to show.")
                return@runBlocking
            }

            val semaphore = Semaphore(permits = 5)

            coroutineScope {
                val results = notifications.mapIndexed { index, notification ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            processNotification(githubApi, index, notification)
                        }
                    }
                }.awaitAll()

                results.sortedBy { it.index }.forEach { result ->
                    printNotificationResult(result)
                }
            }
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

sealed class NotificationResult {
    abstract val index: Int
    abstract val notification: Notification

    data class Success(
        override val index: Int,
        override val notification: Notification,
        val prStatus: String? = null,
        val prUrl: String? = null,
        val actions: List<String> = emptyList(),
    ) : NotificationResult()

    data class Error(
        override val index: Int,
        override val notification: Notification,
        val error: String,
    ) : NotificationResult()
}

private fun matchesAutoApproveCriteria(title: String): Boolean {
    return title.startsWith("Updating appfile", ignoreCase = true)
            && (title.contains("demo", ignoreCase = true)
            || title.contains("to dev", ignoreCase = true))
}

private data class PullRequestResult(
    val status: String,
    val htmlUrl: String?,
    val actions: List<String>,
)

private suspend fun processPullRequestNotification(
    githubApi: GitHubRestApi,
    notification: Notification,
    prUrl: String,
): PullRequestResult {
    val actions = mutableListOf<String>()

    val pr = githubApi.getPullRequestByUrl(prUrl)
    val status = when {
        pr.mergedAt != null -> "merged"
        pr.state?.equals("closed", ignoreCase = true) == true -> "closed"
        else -> pr.state ?: "unknown"
    }

    val title = notification.subject.title
    val isOpenPr = pr.mergedAt == null && pr.state?.equals("closed", ignoreCase = true) != true

    if (matchesAutoApproveCriteria(title) && isOpenPr) {
        try {
            val alreadyApproved = githubApi.hasAnyApprovedReview(prUrl)
            if (alreadyApproved) {
                actions.add("PR already approved; skipping additional approval ${pr.url} ${notification.subject.title}")
            } else {
                githubApi.approvePullRequestByUrl(
                    prUrl,
                    body = "Auto-approving demo appfile update"
                )
                actions.add("Approved PR based on title match ${pr.url} ${notification.subject.title}")
            }
            try {
                githubApi.markNotificationAsDone(notification.id)
                actions.add("Marked notification as done")
            } catch (e: Exception) {
                actions.add("Failed to mark notification as done (${e.message})")
            }
        } catch (e: Exception) {
            actions.add("Failed to check/approve PR ${pr.url} ${notification.subject.title} (${e.message})")
        }
    }

    if (pr.mergedAt != null || pr.state?.equals("closed", ignoreCase = true) == true) {
        try {
            githubApi.markNotificationAsDone(notification.id)
            actions.add("Marked notification as done")
        } catch (e: Exception) {
            actions.add("Failed to mark notification as done (${e.message})")
        }
    }

    return PullRequestResult(status, pr.htmlUrl, actions)
}

private suspend fun processNotification(
    githubApi: GitHubRestApi,
    index: Int,
    notification: Notification,
): NotificationResult {
    return try {
        if (notification.subject.type.equals("PullRequest", ignoreCase = true)) {
            val prUrl = notification.subject.url
            if (prUrl != null) {
                val prResult = processPullRequestNotification(githubApi, notification, prUrl)
                NotificationResult.Success(
                    index = index,
                    notification = notification,
                    prStatus = prResult.status,
                    prUrl = prResult.htmlUrl,
                    actions = prResult.actions
                )
            } else {
                NotificationResult.Success(
                    index = index,
                    notification = notification,
                    prStatus = "unavailable (no subject URL)"
                )
            }
        } else {
            NotificationResult.Success(
                index = index,
                notification = notification
            )
        }
    } catch (e: Exception) {
        NotificationResult.Error(
            index = index,
            notification = notification,
            error = e.message ?: "Unknown error"
        )
    }
}

private fun printNotificationResult(result: NotificationResult) {
    val n = result.notification
    println("\n#${result.index + 1}")
    println("  Thread ID: ${n.id}")
    println("  Repository: ${n.repository.fullName}")
    println("  Subject: ${n.subject.title} (${n.subject.type})")
    println("  Reason: ${n.reason}")
    println("  Unread: ${n.unread}")
    println("  Updated: ${formatDate(n.updatedAt)}")
    n.lastReadAt?.let { println("  Last read: ${formatDate(it)}") }

    when (result) {
        is NotificationResult.Success -> {
            result.prStatus?.let { println("  PR Status: $it") }
            result.prUrl?.let { println("  PR URL: $it") }
            result.actions.forEach { action ->
                println("  Action: $action")
            }
        }

        is NotificationResult.Error -> {
            if (n.subject.type.equals("PullRequest", ignoreCase = true)) {
                println("  PR Status: failed to fetch (${result.error})")
            }
        }
    }
}

private fun formatDate(instant: Instant): String {
    return instant.toString().replace('T', ' ').removeSuffix("Z")
}
